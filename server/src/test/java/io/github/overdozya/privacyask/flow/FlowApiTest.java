package io.github.overdozya.privacyask.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.overdozya.privacyask.PrivacyAskApplication;
import io.github.overdozya.privacyask.issues.ExampleCaseDb;
import io.github.overdozya.privacyask.knowledge.LoadedDb;
import io.github.overdozya.privacyask.llm.FakeProvider;
import io.github.overdozya.privacyask.llm.LlmProvider;
import io.github.overdozya.privacyask.llm.ProviderRequest;
import io.github.overdozya.privacyask.llm.ProviderResponse;
import io.github.overdozya.privacyask.rules.Condition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 06 7절 S7: 서버를 실제로 띄워(HTTP, 테스트 MySQL 적재본, FakeProvider) API 7개를 부른다.
 * ① 예시 사안을 API 1·2로 끝까지 → 사실·슬롯·쟁점·예비 출구가 예시 재현표(case.db = S5 결과)와 같다.
 * ② LLM_FAILED → API 3으로 이어짐, 동시 답 → 409, 허용 값 밖 → 422(저장 안 됨). API 4~7 각 1건. 턴마다 turn_logs.
 * 예시 사안 손 출력은 data/수집/예시사안.json(우리가 지음)의 A1·A3 출력을 지금 스키마 꼴로 옮긴 것. 평가셋 문장은 쓰지 않는다.
 */
class FlowApiTest {
    static final Path EXAMPLE = LoadedDb.DATA.resolve("수집").resolve("예시사안.json");
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String WHO = "t-s7";

    static ConfigurableApplicationContext ctx;
    static FakeProvider fake;
    static HttpClient http;
    static String base;
    static JdbcClient jdbc;
    static String content;
    static String a1Json;
    static final Map<String, Function<ProviderRequest, ProviderResponse>> routes = new ConcurrentHashMap<>();

    @BeforeAll
    static void start() throws Exception {
        assumeThat(LoadedDb.available() && ExampleCaseDb.available() && Files.isRegularFile(EXAMPLE))
                .as("적재 파일·Docker·case.db·예시사안.json 없음").isTrue();
        LoadedDb.dataSource();
        ctx = new SpringApplicationBuilder(PrivacyAskApplication.class)
                .run("--server.port=0",
                        "--spring.datasource.url=" + LoadedDb.url(LoadedDb.container().getDatabaseName()),
                        "--spring.datasource.username=" + LoadedDb.container().getUsername(),
                        "--spring.datasource.password=" + LoadedDb.container().getPassword(),
                        "--app.llm.provider=fake", "--app.data-dir=" + LoadedDb.DATA);
        fake = (FakeProvider) ctx.getBean(LlmProvider.class);
        fake.fallback(r -> routes.getOrDefault(r.promptId(), x -> FakeProvider.ok("{}")).apply(r));
        base = "http://127.0.0.1:" + ctx.getEnvironment().getProperty("local.server.port");
        http = HttpClient.newHttpClient();
        jdbc = LoadedDb.jdbc();
        JsonNode ex = JSON.readTree(Files.readString(EXAMPLE));
        content = ex.get("events").get(0).get("content").asString();
        a1Json = a1FromExample(ex.get("events").get(4).get("output"));
    }

    @AfterAll
    static void stop() {
        if (ctx != null) {
            ctx.close();
        }
    }

    @BeforeEach
    void exampleRoutes() {
        routes.clear();
        routes.put("A0_intake", r -> FakeProvider.ok("""
                {"signals":[{"kind":"OWN_CASE","quote":"T 멤버십 회원에게"},
                            {"kind":"ACTION","quote":"국내 문자 발송 대행 업체에 맡기려고 합니다"},
                            {"kind":"ACTION","quote":"제휴 카페 브랜드에 넘겨서"}]}"""));
        routes.put("A1_facts", r -> FakeProvider.ok(a1Json));
        routes.put("A3_ledger", FlowApiTest::ledgerCites);
        routes.put("A3_watch", r -> FakeProvider.ok("{\"flags\":[]}"));
        routes.put("A5_answer", r -> FakeProvider.ok(answerFromInput(r, false)));
        routes.put("A5_general", r -> FakeProvider.ok(
                "{\"picks\":[{\"key\":\"개인정보보호법|26|\",\"why\":\"위탁 문서 기재 사항\"}],\"no_basis\":false,"
                        + "\"reason\":\"위탁 계약서에 무엇을 적는지 묻는 일반 질문\"}"));
        routes.put("A5_general_answer", r -> FakeProvider.ok(
                "{\"answer\":\"손으로 쓴 답 문장.\",\"cites\":[\"개인정보보호법|26|\"]}"));
    }

    /** S8 A5 손 출력: 넘긴 흐름·쟁점마다 첫 원문 키를 인용하고, 그 흐름 원장 대조의 첫 행을 단다(conclusive는 인자). 받은 입력대로 짓는다. */
    static String answerFromInput(ProviderRequest r, boolean conclusive) {
        JsonNode in = JSON.readTree(r.user().split("\n\n\\[다시 요청]")[0]);
        ObjectNode out = JSON.createObjectNode();
        ArrayNode issues = out.putArray("issues");
        for (JsonNode f : in.get("flows")) {
            String row = null;
            for (JsonNode l : f.get("ledger")) {
                if (row == null && l.get("rows").size() > 0) {
                    row = l.get("rows").get(0).asString();
                }
            }
            for (JsonNode i : f.get("issues")) {
                ObjectNode o = issues.addObject();
                o.put("flow", f.get("flow").asInt());
                o.put("issue", i.get("issue").asString());
                o.put("opinion", "손으로 쓴 의견");
                o.putArray("actions").add("손으로 쓴 조치");
                o.putArray("cites").add(i.get("keys").get(0).get("key").asString());
                ArrayNode rows = o.putArray("ledger_rows");
                if (row != null) {
                    rows.add(row);
                }
                o.put("conclusive", conclusive);
            }
        }
        out.putArray("gaps");
        return out.toString();
    }

    /** 예시 A1 출력(옛 꼴: values [[라벨, 내용]], flow 숫자·목록, NOT_FOUND 포함)을 지금 A1_facts 스키마로. 모르는 사실은 내지 않는다. */
    static String a1FromExample(JsonNode old) {
        ObjectNode out = JSON.createObjectNode();
        ArrayNode flows = out.putArray("flows");
        for (JsonNode f : old.get("flows")) {
            ObjectNode n = ((ObjectNode) f.deepCopy());
            n.put("counterparty_cloud_it", false);
            flows.add(n);
        }
        ArrayNode facts = out.putArray("facts");
        for (JsonNode f : old.get("facts")) {
            if ("NOT_FOUND".equals(f.get("status").asString())) {
                continue;
            }
            ObjectNode n = facts.addObject();
            n.put("fact", f.get("fact").asString());
            ArrayNode fl = n.putArray("flows");
            JsonNode flow = f.get("flow");
            if (flow.isArray()) {
                flow.forEach(x -> fl.add(x.asInt()));
            } else if (!flow.isNull()) {
                fl.add(flow.asInt());
            }
            ArrayNode vs = n.putArray("values");
            for (JsonNode v : f.get("values")) {
                ObjectNode o = vs.addObject();
                if (v.isArray()) {
                    o.put("label", v.get(0).asString());
                    o.put("detail", v.get(1).asString());
                } else {
                    o.put("label", v.asString());
                }
            }
            n.put("status", f.get("status").asString());
            n.put("quote", f.get("quote").asString());
        }
        return out.toString();
    }

    /** 예시 원장 인용: 흐름 1 L1 = 첨부1-0040 명시('멤버십 관련 고지사항 전달'), 나머지 묶음은 모두 없음. 받은 입력의 묶음대로 짓는다. */
    static ProviderResponse ledgerCites(ProviderRequest r) {
        JsonNode in = JSON.readTree(r.user().split("\n\n\\[다시 요청]")[0]);
        ObjectNode out = JSON.createObjectNode();
        ArrayNode cites = out.putArray("cites");
        for (JsonNode g : in.get("groups")) {
            String fact = g.get("fact").asString();
            boolean has0040 = false;
            for (JsonNode c : g.get("candidates")) {
                has0040 |= "첨부1-0040".equals(c.get("row_id").asString());
            }
            for (JsonNode fl : g.get("flows")) {
                ObjectNode c = cites.addObject();
                c.put("flow", fl.asInt());
                c.put("fact", fact);
                ArrayNode rows = c.putArray("rows");
                if ("L1".equals(fact) && fl.asInt() == 1 && has0040) {
                    c.put("match", "명시");
                    ObjectNode row = rows.addObject();
                    row.put("row_id", "첨부1-0040");
                    row.put("quote", "멤버십 관련 고지사항 전달");
                } else {
                    c.put("match", "없음");
                }
            }
        }
        return FakeProvider.ok(out.toString());
    }

    // ───────── HTTP ─────────

    record Res(int status, JsonNode body) {}

    static Res call(String method, String path, Object body, String who) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json");
        if (who != null) {
            b.header("X-Requester-Id", who);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(res.statusCode(), res.body().isEmpty() ? null : JSON.readTree(res.body()));
    }

    static Res post(String path, Object body) throws Exception {
        return call("POST", path, body, WHO);
    }

    static Res get(String path) throws Exception {
        return call("GET", path, null, WHO);
    }

    static Res create() throws Exception {
        return post("/api/cases", Map.of("content", content));
    }

    static Map<String, Object> answer(int turn, String... choices) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("question_turn", turn);
        m.put("choices", List.of(choices));
        return m;
    }

    static String key(JsonNode q) {
        return (q.get("flow").isNull() ? "공통" : q.get("flow").asString()) + ":" + q.get("fact_id").asString();
    }

    // ───────── ① 예시 사안 끝까지 ─────────

    @Test
    void exampleCaseEndToEndMatchesReplay() throws Exception {
        Map<String, String> answers = Map.of("1:F-T4", "아니오", "1:F-C2", "잘 모르겠음", "2:F-C2", "아니오",
                "2:F-B3", "앞으로 받을 예정");
        Res r = create();
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(200);
        String id = r.body().get("case_id").asString();
        assertThat(r.body().get("intake").get("type").asString()).isEqualTo("SPECIFIC");
        List<String> asked = new ArrayList<>();
        List<JsonNode> timings = new ArrayList<>(List.of(r.body().get("timing")));
        for (int i = 0; i < 10 && "QUESTION".equals(r.body().get("outcome").asString()); i++) {
            JsonNode q = r.body().get("question");
            asked.add(key(q) + "#" + q.get("ask_no").asInt());
            String choice = answers.get(key(q));
            assertThat(choice).as("예시에 없는 질문 " + key(q)).isNotNull();
            r = post("/api/cases/" + id + "/answers", answer(q.get("turn_no").asInt(), choice));
            assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(200);
            timings.add(r.body().get("timing"));
        }
        System.out.println("S7 예시 사안 질문: " + asked);
        System.out.println("S7 예시 사안 턴 timing: " + timings);
        // S7b 멈춤 규칙(IMPACT 순서, 상한 6): 같은 다섯 질문, 쟁점 이름을 바꾸는 F-C2가 조건만 바꾸는 F-T4보다 앞
        assertThat(asked).containsExactly("1:F-C2#1", "1:F-C2#2", "2:F-C2#1", "2:F-B3#1", "1:F-T4#1");
        assertThat(r.body().get("outcome").asString()).isEqualTo("RESULT");
        JsonNode res = r.body().get("result");
        assertThat(res.get("preliminary").asBoolean()).isTrue();

        // 슬롯(상태·값·미확인·근거 사실 집합)·쟁점·등급·출구 = 재현표
        for (int n : ExampleCaseDb.flowNos()) {
            JsonNode flow = null;
            for (JsonNode f : res.get("flows")) {
                if (f.get("flow").asInt() == n) {
                    flow = f;
                }
            }
            assertThat(flow).isNotNull();
            for (Map.Entry<String, Map<String, Object>> e : ExampleCaseDb.judgedSlots(n).entrySet()) {
                JsonNode s = flow.get("slots").get(e.getKey());
                assertThat(s.get("status").asString()).as(n + " " + e.getKey()).isEqualTo(e.getValue().get("status"));
                assertThat(Condition.strings(s.get("values"))).as(n + " " + e.getKey()).isEqualTo(e.getValue().get("values"));
                assertThat(Condition.strings(s.get("unknown_facts"))).as(n + " " + e.getKey())
                        .isEqualTo(e.getValue().get("unknown_facts"));
                // 근거 사실: 재현표 = 지금 근거에서 원장 사실 L2~L4를 뺀 것(예시 사안은 L1만 적었고, 지금 기준선은 흐름마다 L1~L4를 적는다)
                List<String> basis = Condition.strings(s.get("basis_facts"));
                List<String> ledgerExtra = basis.stream().filter(b -> b.matches("L[234]")).toList();
                if (!ledgerExtra.isEmpty()) {
                    System.out.println("S7 예시 사안: 흐름 " + n + " " + e.getKey() + " 근거에 " + ledgerExtra + " 더 있음");
                }
                assertThat(basis.stream().filter(b -> !b.matches("L[234]")).toList()).as(n + " " + e.getKey() + " 근거")
                        .containsExactlyInAnyOrderElementsOf((List<String>) e.getValue().get("basis_facts"));
            }
            List<List<Object>> mine = new ArrayList<>();
            for (JsonNode i : flow.get("issues")) {
                mine.add(List.of(i.get("name").asString(), i.get("package_id").asString(), i.get("grade").asString(),
                        Condition.strings(i.get("rule_ids")), Condition.strings(i.get("condition_ids")),
                        Condition.strings(i.get("interp_on"))));
            }
            assertThat(mine).as("흐름 " + n + " 쟁점").isEqualTo(ExampleCaseDb.judgedIssues(n));
        }
        JsonNode j = ExampleCaseDb.judgment();
        assertThat(res.get("case_grade").asString()).isEqualTo(j.get("case_grade").asString()).isEqualTo("H");
        assertThat(res.get("exit_type").asString()).isEqualTo(j.get("exit_type").asString()).isEqualTo("TRANSFER");
        assertThat(res.get("exit_reasons").toString())
                .isEqualTo("[{\"code\":\"REQUESTER_UNKNOWN\",\"flow\":1,\"detail\":\"cross_border: F-C2\"}]");
        assertThat(res.get("watch_flag").asBoolean()).isFalse();
        assertThat(res.get("unasked")).isEmpty();
        // S8 ⑤: 답(A5)이 붙음. 쟁점 5개마다 의견·인용(꾸러미 원문 키), 원문 요약은 코드가 붙임. 기준일 = 예시 F-S3 2026-12-01
        JsonNode ans = res.get("answer");
        System.out.println("S8 예시 사안 답: " + ans);
        assertThat(ans.get("kind").asString()).isEqualTo("SPECIFIC");
        assertThat(ans.get("reference_date").asString()).isEqualTo("2026-12-01");
        assertThat(ans.get("open_date").asString()).isEqualTo("2026-12-01");
        assertThat(ans.get("issues")).hasSize(5);
        assertThat(ans.get("issues")).allSatisfy(i -> assertThat(i.get("cites").size()).isPositive());
        assertThat(ans.get("sources").size()).isPositive();
        assertThat(ans.get("sources")).allSatisfy(x -> assertThat(x.get("text_hash").asString()).isNotBlank());
        assertThat(ans.get("ledger_rows").get(0).get("row_id").asString()).isEqualTo("첨부1-0040");
        assertThat(ans.get("conclusive").asBoolean()).isFalse();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM steps WHERE case_id=? AND step='A4' AND name='꾸러미 열기'").param(id)
                .query(Long.class).single()).isEqualTo(1L);

        // 사실: 재현표의 지금 사실(흐름·사실·상태·값)이 모두 같다. 원장 사실 L2~L4(지금 기준선이 흐름마다 넷을 적음)는 더 있음
        Map<String, String> want = new TreeMap<>();
        for (JsonNode f : ExampleCaseDb.query("SELECT f.flow_no, f.fact_id, f.status, "
                + "(SELECT json_group_array(label) FROM (SELECT label FROM fact_values v WHERE v.fact_row_id=f.fact_row_id ORDER BY ord)) AS vals "
                + "FROM facts_current f")) {
            want.put((f.get("flow_no").isNull() ? "공통" : f.get("flow_no").asString()) + ":" + f.get("fact_id").asString(),
                    f.get("status").asString() + " " + Condition.strings(JSON.readTree(f.get("vals").asString())));
        }
        Map<String, String> got = new TreeMap<>();
        JsonNode view = get("/api/cases/" + id).body();
        for (JsonNode f : view.get("facts")) {
            List<String> labels = new ArrayList<>();
            f.get("values").forEach(v -> labels.add(v.get("label").asString()));
            got.put((f.get("flow").isNull() ? "공통" : f.get("flow").asString()) + ":" + f.get("fact_id").asString(),
                    f.get("status").asString() + " " + labels);
        }
        Map<String, String> extra = new TreeMap<>(got);
        want.keySet().forEach(extra::remove);
        System.out.println("S7 예시 사안: 재현표에 없는 사실 " + extra);
        for (Map.Entry<String, String> e : want.entrySet()) {
            assertThat(got.get(e.getKey())).as("사실 " + e.getKey()).isEqualTo(e.getValue());
        }
        assertThat(extra.keySet()).allMatch(k -> k.matches("\\d+:L[234]"));

        // turn_logs: 요청 6개(만들기 1 + 답 5), 첫 턴 LLM 3(A0 ‖ A1 + A3 원장) 직렬 2, 가운데 답 0, 마지막 답 1(감시)
        List<Map<String, Object>> logs = jdbc.sql("SELECT request_kind, llm_calls, llm_depth, db_statements, total_ms, "
                + "result_code FROM turn_logs WHERE case_id=? ORDER BY turn_log_id").param(id).query().listOfRows();
        System.out.println("S7 예시 사안 turn_logs: " + logs);
        assertThat(logs).hasSize(6);
        assertThat(logs.get(0)).containsEntry("request_kind", "CREATE").containsEntry("llm_calls", 3)
                .containsEntry("llm_depth", 2).containsEntry("result_code", "OK_QUESTION");
        for (int i = 1; i < 5; i++) {
            assertThat(logs.get(i)).containsEntry("request_kind", "ANSWER").containsEntry("llm_calls", 0)
                    .containsEntry("llm_depth", 0);
        }
        // S8: 마지막 답 = 감시 1 + A5 답변 1(직렬)
        assertThat(logs.get(5)).containsEntry("llm_calls", 2).containsEntry("llm_depth", 2)
                .containsEntry("result_code", "OK_RESULT");
        assertThat(logs).allSatisfy(l -> assertThat(((Number) l.get("db_statements")).intValue()).isPositive());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM llm_call_logs WHERE case_id=? AND (step_seq IS NULL OR discarded=1)")
                .param(id).query(Long.class).single()).isZero();

        // 다 끝난 뒤 다시 시도(API 3)는 같은 결과를 그대로(단계를 더 쓰지 않음)
        long steps = jdbc.sql("SELECT COUNT(*) FROM steps WHERE case_id=?").param(id).query(Long.class).single();
        Res again = post("/api/cases/" + id + "/retry", null);
        assertThat(again.body().get("outcome").asString()).isEqualTo("RESULT");
        assertThat(again.body().get("result")).isEqualTo(res);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM steps WHERE case_id=?").param(id).query(Long.class).single())
                .isEqualTo(steps);
    }

    /** LLM 없는 턴(보기 답, 질문이 남음)의 서버 시간 p95(06 6.1 목표 300 ms): 예시 사안 가운데 답 턴을 여러 사안으로 모아 잰다. */
    @Test
    void choiceTurnLatency() throws Exception {
        Map<String, String> answers = Map.of("1:F-T4", "아니오", "1:F-C2", "잘 모르겠음", "2:F-C2", "아니오",
                "2:F-B3", "앞으로 받을 예정");
        List<Integer> ms = new ArrayList<>();
        List<Integer> db = new ArrayList<>();
        for (int c = 0; c < 6; c++) {
            Res r = create();
            String id = r.body().get("case_id").asString();
            while ("QUESTION".equals(r.body().get("outcome").asString())) {
                JsonNode q = r.body().get("question");
                r = post("/api/cases/" + id + "/answers", answer(q.get("turn_no").asInt(), answers.get(key(q))));
                if ("QUESTION".equals(r.body().get("outcome").asString()) && c > 0) {   // 첫 사안은 데우기
                    ms.add(r.body().get("timing").get("turn_ms").asInt());
                    db.add(r.body().get("timing").get("db_statements").asInt());
                }
            }
        }
        List<Integer> sorted = ms.stream().sorted().toList();
        int p50 = sorted.get(sorted.size() / 2), p95 = sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
        System.out.println("S7 LLM 없는 턴(보기 답·질문 남음) " + sorted.size() + "건 ms p50 " + p50 + " p95 " + p95
                + " 최대 " + sorted.get(sorted.size() - 1) + " / DB 문장 " + db.stream().sorted().toList());
        assertThat(p95).isLessThan(300);
    }

    // ───────── ② 오류 3건 ─────────

    @Test
    void llmFailedThenRetryContinues() throws Exception {
        routes.put("A1_facts", r -> FakeProvider.ok("JSON 아님"));
        Res r = create();
        assertThat(r.status()).isEqualTo(503);
        assertThat(r.body().get("code").asString()).isEqualTo("LLM_FAILED");
        String id = r.body().get("details").get("case_id").asString();
        assertThat(r.body().get("details").get("turn_no").asInt()).isZero();
        assertThat(jdbc.sql("SELECT status FROM steps WHERE case_id=? AND step='A1' AND actor='LLM'").param(id)
                .query(String.class).list()).containsExactly("LLM_FAILED");
        Res peek = get("/api/cases/" + id);
        assertThat(peek.body().get("last").get("outcome").asString()).isEqualTo("PENDING");
        assertThat(peek.body().get("last").get("next_action").asString()).isEqualTo("A1_INQUIRY");

        routes.put("A1_facts", r2 -> FakeProvider.ok(a1Json));
        Res again = post("/api/cases/" + id + "/retry", null);
        assertThat(again.status()).as(String.valueOf(again.body())).isEqualTo(200);
        assertThat(again.body().get("outcome").asString()).isEqualTo("QUESTION");
        assertThat(key(again.body().get("question"))).isEqualTo("1:F-C2");
        assertThat(jdbc.sql("SELECT status FROM steps WHERE case_id=? AND step='A1' AND actor='LLM' ORDER BY seq")
                .param(id).query(String.class).list()).containsExactly("LLM_FAILED", "OK");
        assertThat(jdbc.sql("SELECT request_kind, result_code FROM turn_logs WHERE case_id=? ORDER BY turn_log_id")
                .param(id).query((rs, i) -> rs.getString(1) + " " + rs.getString(2)).list())
                .containsExactly("CREATE LLM_FAILED", "RETRY OK_QUESTION");
    }

    @Test
    void concurrentAnswersOneWins409() throws Exception {
        Res r = create();
        String id = r.body().get("case_id").asString();
        int turn = r.body().get("question").get("turn_no").asInt();
        int n = 6;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<Res>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                return post("/api/cases/" + id + "/answers", answer(turn, "아니오"));
            }));
        }
        go.countDown();
        Map<String, Integer> codes = new HashMap<>();
        for (Future<Res> f : fs) {
            Res x = f.get();
            codes.merge(x.status() == 200 ? "200" : x.status() + " " + x.body().get("code").asString(), 1, Integer::sum);
        }
        pool.shutdown();
        System.out.println("S7 동시 답 " + n + "개: " + codes);
        assertThat(codes.get("200")).isEqualTo(1);
        assertThat(codes.keySet()).allMatch(k -> k.equals("200") || k.startsWith("409 "));
        assertThat(codes.getOrDefault("409 TURN_CONFLICT", 0)).as("잠금에서 막힌 늦은 답").isPositive();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM turns WHERE case_id=? AND kind='ANSWER' AND reply_to=?")
                .params(id, turn).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void invalidAnswer422NotStored() throws Exception {
        Res r = create();
        String id = r.body().get("case_id").asString();
        JsonNode q = r.body().get("question");
        int turn = q.get("turn_no").asInt();
        String before = rows(id);
        Res bad = post("/api/cases/" + id + "/answers", answer(turn, "어쩌면"));
        assertThat(bad.status()).isEqualTo(422);
        assertThat(bad.body().get("code").asString()).isEqualTo("INVALID_ANSWER");
        assertThat(bad.body().get("question")).isEqualTo(q);
        Res two = post("/api/cases/" + id + "/answers", answer(turn, "예", "아니오"));
        assertThat(two.status()).isEqualTo(422);
        assertThat(rows(id)).isEqualTo(before);
        // 같은 질문에 맞는 답은 그 뒤에 받는다
        assertThat(post("/api/cases/" + id + "/answers", answer(turn, "아니오")).status()).isEqualTo(200);
    }

    static String rows(String id) {
        return jdbc.sql("SELECT CONCAT((SELECT COUNT(*) FROM turns WHERE case_id=?),'/',(SELECT COUNT(*) FROM facts WHERE case_id=?),'/',"
                + "(SELECT COUNT(*) FROM steps WHERE case_id=?))").params(id, id, id).query(String.class).single();
    }

    @Test
    void notTheQuestionAndNotFound() throws Exception {
        Res r = create();
        String id = r.body().get("case_id").asString();
        int turn = r.body().get("question").get("turn_no").asInt();
        Res wrong = post("/api/cases/" + id + "/answers", answer(turn + 5, "아니오"));
        assertThat(wrong.status()).isEqualTo(409);
        assertThat(wrong.body().get("code").asString()).isEqualTo("NOT_THE_QUESTION");
        Res other = call("GET", "/api/cases/" + id, null, "someone-else");
        assertThat(other.status()).isEqualTo(404);
        assertThat(other.body().get("code").asString()).isEqualTo("CASE_NOT_FOUND");
    }

    // ───────── 입구 갈래·추측 A1 ─────────

    @Test
    void generalDiscardsSpeculativeA1() throws Exception {
        routes.put("A0_intake", r -> FakeProvider.ok("{\"signals\":[]}"));
        Res r = create();
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("outcome").asString()).isEqualTo("GENERAL");
        assertThat(r.body().get("state").asString()).isEqualTo("ASKING");
        String id = r.body().get("case_id").asString();
        long discarded = 0;
        for (int i = 0; i < 50 && discarded == 0; i++) {   // 버린 A1은 응답 뒤에 끝날 수 있다
            Thread.sleep(50);
            discarded = jdbc.sql("SELECT COUNT(*) FROM llm_call_logs WHERE case_id=? AND prompt_id='A1_facts' AND discarded=1")
                    .param(id).query(Long.class).single();
        }
        assertThat(discarded).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM steps WHERE case_id=? AND step='A1'").param(id).query(Long.class)
                .single()).isZero();
        // S8: A0 → A5 일반(G2) → (S8b) 답 문장 직렬. 결과는 고른 조의 원문(코드가 붙임) + 답 문장
        assertThat(r.body().get("timing").get("llm_depth").asInt()).isEqualTo(3);
        JsonNode ans = r.body().get("result").get("answer");
        assertThat(ans.get("kind").asString()).isEqualTo("GENERAL");
        assertThat(ans.get("picks").get(0).get("key").asString()).isEqualTo("개인정보보호법|26|");
        assertThat(ans.get("sources").size()).isPositive();
        assertThat(ans.get("sources")).allSatisfy(x -> assertThat(x.get("key").asString()).startsWith("개인정보보호법|26|"));
        assertThat(r.body().get("result").get("exit_type").asString()).isEqualTo("ANSWER");
        assertThat(ans.get("answer_text").asString()).isEqualTo("손으로 쓴 답 문장.");
        assertThat(Condition.strings(ans.get("answer_cites"))).containsExactly("개인정보보호법|26|");
        assertThat(get("/api/cases/" + id).body().get("last").get("result").get("answer").get("kind").asString())
                .isEqualTo("GENERAL");
    }

    @Test
    void outOfScopeTransferred() throws Exception {
        routes.put("A0_intake", r -> FakeProvider.ok(
                "{\"signals\":[{\"kind\":\"INCIDENT\",\"quote\":\"제휴 카페 브랜드에 넘겨서\"}]}"));
        Res r = create();
        assertThat(r.body().get("outcome").asString()).isEqualTo("TRANSFERRED");
        assertThat(r.body().get("state").asString()).isEqualTo("TRANSFERRED");
        assertThat(r.body().get("intake").get("out_of_scope_kind").asString()).isEqualTo("BREACH");
        String id = r.body().get("case_id").asString();
        Res ans = post("/api/cases/" + id + "/answers", answer(1, "아니오"));
        assertThat(ans.status()).isEqualTo(409);
        assertThat(ans.body().get("code").asString()).isEqualTo("NOT_ASKING");
    }

    // ───────── API 4~7 ─────────

    @Test
    void api4CaseView() throws Exception {
        String id = create().body().get("case_id").asString();
        Res v = get("/api/cases/" + id);
        assertThat(v.status()).isEqualTo(200);
        assertThat(v.body().get("case_info").get("service_name").asString()).isEqualTo("T 멤버십");
        assertThat(v.body().get("flows")).hasSize(2);
        assertThat(v.body().get("facts").size()).isGreaterThan(20);
        assertThat(v.body().get("turns")).hasSize(1);
        assertThat(v.body().get("last").get("outcome").asString()).isEqualTo("QUESTION");
        assertThat(v.body().get("last").get("question").get("fact_id").asString()).isEqualTo("F-C2");
    }

    @Test
    void api5Trace() throws Exception {
        String id = create().body().get("case_id").asString();
        Res t = get("/api/cases/" + id + "/trace");
        assertThat(t.status()).isEqualTo(200);
        List<String> names = new ArrayList<>();
        t.body().get("steps").forEach(s -> names.add(s.get("step").asString() + " " + s.get("name").asString()));
        assertThat(names).startsWith("A0 입구 신호 표시", "A0 입구 판정", "A1 사실 추출(문의)").contains("A2 다음 질문 고르기");
        assertThat(t.body().get("steps").get(0).get("output").get("signals")).isNotNull();
        assertThat(t.body().get("calls")).hasSize(3);
        assertThat(t.body().get("calls")).allSatisfy(c -> assertThat(c.get("step_seq").isNull()).isFalse());
    }

    @Test
    void api6List() throws Exception {
        String id = call("POST", "/api/cases", Map.of("content", content), "t-s7-list").body().get("case_id").asString();
        Res l = call("GET", "/api/cases?page=0&size=5", null, "t-s7-list");
        assertThat(l.status()).isEqualTo(200);
        assertThat(l.body().get("cases").get(0).get("case_id").asString()).isEqualTo(id);
        assertThat(call("GET", "/api/cases", null, "nobody-s7").body().get("cases")).isEmpty();
    }

    @Test
    void api7Knowledge() throws Exception {
        Res k = get("/api/knowledge");
        assertThat(k.status()).isEqualTo(200);
        assertThat(k.body().get("versions").get("rule_set").asString()).isEqualTo("v1.0");
        assertThat(k.body().get("versions").get("package_current").asString()).isEqualTo("v2");
        assertThat(k.body().get("provider").asString()).isEqualTo("fake");
        List<String> ids = new ArrayList<>();
        k.body().get("prompts").forEach(p -> ids.add(p.get("prompt_id").asString()));
        assertThat(ids).contains("A0_intake", "A1_facts", "A3_ledger", "A3_watch");
    }

    // ───────── S7b 고침 ─────────

    /** S7b 1: A0 (마)의 영역 이름('위치정보')은 법령 꼴이 아니라 건너뛰고(이관 사유 없음) A2 단계 input law_skipped에 남는다. */
    @Test
    void areaNameSkippedNotTransferReason() throws Exception {
        routes.put("A0_intake", r -> FakeProvider.ok("""
                {"signals":[{"kind":"OWN_CASE","quote":"T 멤버십 회원에게"},
                            {"kind":"LAW_AREA","quote":"위치정보","name":"위치정보"}]}"""));
        String id = create().body().get("case_id").asString();
        JsonNode a2 = JSON.readTree(jdbc.sql("SELECT input_json FROM steps WHERE case_id=? AND step='A2' ORDER BY seq LIMIT 1")
                .param(id).query(String.class).single());
        assertThat(Condition.strings(a2.get("law_skipped"))).containsExactly("위치정보");
        assertThat(a2.get("external")).isEmpty();
    }

    /** S7b 2: 흐름 서비스명이 원장에 없으면 사안 서비스명(API 1)으로 원장 후보를 연다. A1 입력에 case_service_name. */
    @Test
    void caseServiceNameUsedWhenFlowServiceNotInLedger() throws Exception {
        String a1 = a1Json.replace("\"service_name\":\"T 멤버십\"", "\"service_name\":\"멤버십 문자 앱\"");
        assertThat(a1).contains("멤버십 문자 앱");
        routes.put("A1_facts", r -> FakeProvider.ok(a1));
        Res r = post("/api/cases", Map.of("content", content, "service_name", "T 멤버십"));
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(200);
        String id = r.body().get("case_id").asString();
        JsonNode cand = JSON.readTree(jdbc.sql("SELECT output_json FROM steps WHERE case_id=? AND name='원장 후보 행 열거'")
                .param(id).query(String.class).single());
        cand.get("groups").forEach(g -> assertThat(g.get("service").asString()).isEqualTo("T 멤버십"));
        assertThat(fake.requests().stream().filter(x -> x.promptId().equals("A1_facts"))
                .anyMatch(x -> x.user().contains("\"case_service_name\":\"T 멤버십\""))).isTrue();
        // 흐름 1 L1은 명시 일치(첨부1-0040)로 정해진다
        JsonNode v = get("/api/cases/" + id).body();
        boolean l1 = false;
        for (JsonNode f : v.get("facts")) {
            l1 |= "L1".equals(f.get("fact_id").asString()) && f.get("flow").asInt() == 1 && "MATCHED".equals(f.get("status").asString());
        }
        assertThat(l1).isTrue();
    }

    /** S7b 4: 자유 답에 받은 질문의 사실이 없으면 업무 검사가 거절 → 다시 → 그 사실을 내면 같은 질문을 다시 묻지 않는다. */
    @Test
    void freeAnswerMustCoverAskedFact() throws Exception {
        routes.put("A1_facts", r -> {
            if (!r.user().contains("\"source\":\"ANSWER\"")) {
                return FakeProvider.ok(a1Json);
            }
            return FakeProvider.ok(r.user().contains("[다시 요청]")
                    ? "{\"flows\":[],\"facts\":[{\"fact\":\"F-C2\",\"flows\":[1],\"values\":[{\"label\":\"아니오\"}],"
                    + "\"status\":\"INFERRED\",\"quote\":\"서버는 서울 리전에\"}],\"unanswered\":[]}"
                    : "{\"flows\":[],\"facts\":[]}");
        });
        Res r = create();
        String id = r.body().get("case_id").asString();
        JsonNode q = r.body().get("question");
        assertThat(key(q)).isEqualTo("1:F-C2");
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("question_turn", q.get("turn_no").asInt());
        a.put("choices", List.of());
        a.put("text", "업체 말로는 서버는 서울 리전에 있다고 합니다.");
        Res r2 = post("/api/cases/" + id + "/answers", a);
        assertThat(r2.status()).as(String.valueOf(r2.body())).isEqualTo(200);
        assertThat(key(r2.body().get("question"))).isNotEqualTo("1:F-C2");
        assertThat(jdbc.sql("SELECT outcome FROM llm_call_logs WHERE case_id=? AND prompt_id='A1_facts' ORDER BY call_id")
                .param(id).query(String.class).list()).containsExactly("OK", "CHECK_FAIL", "OK");
    }

    // ───────── S8 A4·A5 ─────────

    static final Map<String, String> EXAMPLE_ANSWERS = Map.of("1:F-T4", "아니오", "1:F-C2", "잘 모르겠음", "2:F-C2", "아니오",
            "2:F-B3", "앞으로 받을 예정");

    /** 예시 사안을 질문이 끝날 때까지(마지막 응답을 돌려줌, 503이면 거기서). */
    static Res runExample() throws Exception {
        Res r = create();
        String id = r.body().get("case_id").asString();
        for (int i = 0; i < 10 && r.status() == 200 && "QUESTION".equals(r.body().get("outcome").asString()); i++) {
            JsonNode q = r.body().get("question");
            r = post("/api/cases/" + id + "/answers", answer(q.get("turn_no").asInt(), EXAMPLE_ANSWERS.get(key(q))));
        }
        return r;
    }

    static List<String> outcomes(String id, String prompt) {
        return jdbc.sql("SELECT outcome FROM llm_call_logs WHERE case_id=? AND prompt_id=? ORDER BY call_id")
                .params(id, prompt).query(String.class).list();
    }

    /** ④ 꾸러미 밖 키 인용 → 업무 검사 거절 → 다시(거절 이유가 붙은 요청) → 맞으면 RETRY_OK. */
    @Test
    void answerOutsidePackageKeyRejectedThenRetryOk() throws Exception {
        List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        routes.put("A5_answer", r -> {
            seen.add(r.user());
            if (!r.user().contains("[다시 요청]")) {
                return FakeProvider.ok(answerFromInput(r, true).replaceFirst("\"cites\":\\[\"[^\"]+\"",
                        "\"cites\":[\"개인정보보호법|71|1||\""));
            }
            return FakeProvider.ok(answerFromInput(r, true));
        });
        Res r = runExample();
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(200);
        assertThat(r.body().get("outcome").asString()).isEqualTo("RESULT");
        String id = r.body().get("case_id").asString();
        assertThat(outcomes(id, "A5_answer")).containsExactly("CHECK_FAIL", "OK");
        assertThat(seen.get(1)).contains("넘긴 꾸러미 밖");
        assertThat(jdbc.sql("SELECT status FROM steps WHERE case_id=? AND step='A5'").param(id).query(String.class).list())
                .containsExactly("RETRY_OK");
        // 모델이 모두 확정이라 해도 사안 출구는 예비 출구 그대로(고 등급 → TRANSFER), 쟁점은 확정
        JsonNode ans = r.body().get("result").get("answer");
        assertThat(ans.get("issues")).allSatisfy(i -> assertThat(i.get("conclusive").asBoolean()).isTrue());
        assertThat(r.body().get("result").get("exit_type").asString()).isEqualTo("TRANSFER");
    }

    /** ④ 후보 밖 원장 행 두 번 → LLM_FAILED(503) → 고친 뒤 API 3으로 이어서 결과. 해석 필요 gap → 이관 사유. */
    @Test
    void answerOutsideRowTwiceLlmFailedThenRetry() throws Exception {
        routes.put("A5_answer", r -> FakeProvider.ok(answerFromInput(r, false)
                .replace("\"ledger_rows\":[]", "\"ledger_rows\":[\"첨부1-0039\"]")));
        Res r = runExample();
        assertThat(r.status()).isEqualTo(503);
        assertThat(r.body().get("code").asString()).isEqualTo("LLM_FAILED");
        assertThat(r.body().get("details").get("action").asString()).isEqualTo("A5_SPECIFIC");
        String id = r.body().get("details").get("case_id").asString();
        assertThat(outcomes(id, "A5_answer")).containsExactly("CHECK_FAIL", "CHECK_FAIL");
        assertThat(get("/api/cases/" + id).body().get("last").get("next_action").asString()).isEqualTo("A5_SPECIFIC");

        routes.put("A5_answer", x -> FakeProvider.ok(answerFromInput(x, false).replace("\"gaps\":[]",
                "\"gaps\":[{\"kind\":\"INTERPRETATION\",\"flow\":2,\"issue\":\"목적 외 이용\",\"facts\":[],"
                        + "\"note\":\"제휴 마케팅이 수집 목적과 합리적으로 관련되는지\"}]")));
        Res again = post("/api/cases/" + id + "/retry", null);
        assertThat(again.status()).as(String.valueOf(again.body())).isEqualTo(200);
        JsonNode res = again.body().get("result");
        assertThat(res.get("exit_reasons").toString()).contains("GAP_INTERPRETATION");
        assertThat(res.get("answer").get("gaps").get(0).get("handled").asString()).isEqualTo("REASON");
        // A4는 다시 열지 않음(한 입력에 한 번), A5 단계는 LLM_FAILED 뒤 OK
        assertThat(jdbc.sql("SELECT CONCAT(step,' ',status) FROM steps WHERE case_id=? AND step IN ('A4','A5') ORDER BY seq")
                .param(id).query(String.class).list()).containsExactly("A4 OK", "A5 LLM_FAILED", "A5 OK");
    }

    /** ④ 일반 G2: 목록 밖 키 두 번 → LLM_FAILED. 근거 없음 → 검토 안내(TRANSFER, NO_BASIS). */
    @Test
    void generalOutsideListRejectedAndNoBasis() throws Exception {
        routes.put("A0_intake", r -> FakeProvider.ok("{\"signals\":[]}"));
        routes.put("A5_general", r -> FakeProvider.ok(
                "{\"picks\":[{\"key\":\"개인정보보호법|71|\",\"why\":\"벌칙\"}],\"no_basis\":false,\"reason\":\"r\"}"));
        Res r = create();
        assertThat(r.status()).isEqualTo(503);
        String id = r.body().get("details").get("case_id").asString();
        assertThat(outcomes(id, "A5_general")).containsExactly("CHECK_FAIL", "CHECK_FAIL");
        routes.put("A5_general", x -> FakeProvider.ok("{\"picks\":[],\"no_basis\":true,\"reason\":\"벌칙은 목록 밖\"}"));
        Res again = post("/api/cases/" + id + "/retry", null);
        assertThat(again.body().get("outcome").asString()).isEqualTo("GENERAL");
        assertThat(again.body().get("result").get("exit_type").asString()).isEqualTo("TRANSFER");
        assertThat(again.body().get("result").get("exit_reasons").get(0).get("code").asString()).isEqualTo("NO_BASIS");
        // 고른 조가 없으면 답 문장은 LLM 없이 건너뜀(코드 단계)
        assertThat(outcomes(id, "A5_general_answer")).isEmpty();
        assertThat(again.body().get("result").get("answer").get("answer_text").isNull()).isTrue();
        assertThat(jdbc.sql("SELECT actor FROM steps WHERE case_id=? AND name='답변 문장(일반 질의)'").param(id)
                .query(String.class).list()).containsExactly("CODE");
    }

    /** S8b: 일반 답 문장이 고른 조 밖 키를 인용 → 거절 → 다시 → RETRY_OK. */
    @Test
    void generalTextOutsidePicksRejectedThenRetryOk() throws Exception {
        routes.put("A0_intake", r -> FakeProvider.ok("{\"signals\":[]}"));
        routes.put("A5_general_answer", r -> FakeProvider.ok(r.user().contains("[다시 요청]")
                ? "{\"answer\":\"고친 답.\",\"cites\":[\"개인정보보호법|26|1||\"]}"
                : "{\"answer\":\"답.\",\"cites\":[\"개인정보보호법|17|\"]}"));
        Res r = create();
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(200);
        String id = r.body().get("case_id").asString();
        assertThat(outcomes(id, "A5_general_answer")).containsExactly("CHECK_FAIL", "OK");
        assertThat(r.body().get("result").get("answer").get("answer_text").asString()).isEqualTo("고친 답.");
    }
}
