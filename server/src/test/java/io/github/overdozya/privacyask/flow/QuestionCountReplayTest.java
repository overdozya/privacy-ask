package io.github.overdozya.privacyask.flow;

import static org.assertj.core.api.Assumptions.assumeThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import io.github.overdozya.privacyask.PrivacyAskApplication;
import io.github.overdozya.privacyask.knowledge.LoadedDb;
import io.github.overdozya.privacyask.llm.FakeProvider;
import io.github.overdozya.privacyask.llm.LlmProvider;
import io.github.overdozya.privacyask.llm.ProviderRequest;
import io.github.overdozya.privacyask.llm.ProviderResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * S7b 질문 수 비교(재는 것, S7_REPLAY=1일 때만): 멈춤 규칙 설정마다 서버를 띄워, 예시 사안(손 출력)과 지은 문의 4건(S7 실제 호출의
 * 모델 출력을 그대로 재생, data/수집/S7_재생.json)을 같은 기계적 답(첫 보기 중 '잘 모르겠음'이 아닌 것)으로 끝까지 돌린다.
 * LLM은 부르지 않는다. 결과(질문 수·질문 차례·등급·출구·쟁점)를 S7_REPLAY_OUT(기본 레포 밖 임시)에 낸다.
 */
@EnabledIfEnvironmentVariable(named = "S7_REPLAY", matches = "1")
class QuestionCountReplayTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Path INQ = LoadedDb.DATA.resolve("수집").resolve("S7_지은문의.json");
    static final Path REPLAY = Path.of(System.getenv().getOrDefault("S7_REPLAY_FILE",
            LoadedDb.DATA.resolve("수집").resolve("S7_재생.json").toString()));

    static volatile JsonNode current;   // 지금 돌리는 문의의 재생 출력
    static String base;
    static final HttpClient HTTP = HttpClient.newHttpClient();

    static final Map<String, String[]> VARIANTS = new LinkedHashMap<>();
    static {
        VARIANTS.put("전(ORDER, 상한 없음)", new String[] {"--app.question.priority=ORDER", "--app.question.max-per-case=0"});
        VARIANTS.put("ORDER, 상한 6", new String[] {"--app.question.priority=ORDER", "--app.question.max-per-case=6"});
        VARIANTS.put("IMPACT, 상한 5", new String[] {"--app.question.priority=IMPACT", "--app.question.max-per-case=5"});
        VARIANTS.put("후(IMPACT, 상한 6) = application.yml", new String[] {});
        VARIANTS.put("IMPACT, 상한 8", new String[] {"--app.question.priority=IMPACT", "--app.question.max-per-case=8"});
    }

    @Test
    void compare() throws Exception {
        assumeThat(LoadedDb.available() && Files.isRegularFile(INQ) && Files.isRegularFile(REPLAY)).isTrue();
        JsonNode inq = JSON.readTree(Files.readString(INQ)).get("inquiries");
        JsonNode rep = JSON.readTree(Files.readString(REPLAY)).get("cases");
        JsonNode ex = JSON.readTree(Files.readString(FlowApiTest.EXAMPLE));
        String exContent = ex.get("events").get(0).get("content").asString();
        String exA1 = FlowApiTest.a1FromExample(ex.get("events").get(4).get("output"));
        Map<String, Object> all = new LinkedHashMap<>();
        LoadedDb.dataSource();
        for (Map.Entry<String, String[]> v : VARIANTS.entrySet()) {
            List<String> args = new ArrayList<>(List.of("--server.port=0",
                    "--spring.datasource.url=" + LoadedDb.url(LoadedDb.container().getDatabaseName()),
                    "--spring.datasource.username=" + LoadedDb.container().getUsername(),
                    "--spring.datasource.password=" + LoadedDb.container().getPassword(),
                    "--app.llm.provider=fake", "--app.data-dir=" + LoadedDb.DATA, "--logging.level.root=warn"));
            args.addAll(List.of(v.getValue()));
            Map<String, Object> rows = new LinkedHashMap<>();
            try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(PrivacyAskApplication.class)
                    .run(args.toArray(String[]::new))) {
                base = "http://127.0.0.1:" + ctx.getEnvironment().getProperty("local.server.port");
                FakeProvider fake = (FakeProvider) ctx.getBean(LlmProvider.class);
                // 예시 사안: FlowApiTest의 손 출력, 답은 예시의 답
                fake.fallback(r -> switch (r.promptId()) {
                    case "A0_intake" -> FakeProvider.ok("{\"signals\":[{\"kind\":\"OWN_CASE\",\"quote\":\"T 멤버십 회원에게\"}]}");
                    case "A1_facts" -> FakeProvider.ok(exA1);
                    case "A3_ledger" -> FlowApiTest.ledgerCites(r);
                    case "A5_answer", "A5_general", "A5_general_answer" -> a5(r);
                    default -> FakeProvider.ok("{\"flags\":[]}");
                });
                rows.put("예시 사안", run(exContent, null, null, Map.of("1:F-T4", "아니오", "1:F-C2", "잘 모르겠음",
                        "2:F-C2", "아니오", "2:F-B3", "앞으로 받을 예정")));
                fake.fallback(QuestionCountReplayTest::replay);
                for (JsonNode q : inq) {
                    current = rep.get(q.get("id").asString());
                    rows.put(q.get("id").asString(), run(q.get("content").asString(),
                            q.hasNonNull("service_name") ? q.get("service_name").asString() : null,
                            q.get("free_answer"), Map.of()));
                }
            }
            all.put(v.getKey(), rows);
            System.out.println("S7b " + v.getKey() + ": " + summary(rows));
        }
        Path out = Path.of(System.getenv().getOrDefault("S7_REPLAY_OUT",
                System.getProperty("java.io.tmpdir") + "/s7b_replay.json"));
        Files.writeString(out, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(all));
        System.out.println("S7b 재생 결과 → " + out);
    }

    static String summary(Map<String, Object> rows) {
        StringBuilder sb = new StringBuilder();
        rows.forEach((k, v) -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) v;
            sb.append(k).append(' ').append(m.get("questions")).append("문 ").append(m.get("grade")).append(' ')
                    .append(m.get("exit")).append(m.get("reasons")).append("; ");
        });
        return sb.toString();
    }

    /** S8b: A5 손 출력(질문 수·등급·출구와 무관). 구체는 넘긴 쟁점마다 첫 키 인용, 일반은 보호법 제26조. */
    static ProviderResponse a5(ProviderRequest r) {
        return switch (r.promptId()) {
            case "A5_answer" -> FakeProvider.ok(FlowApiTest.answerFromInput(r, false));
            case "A5_general" -> FakeProvider.ok("{\"picks\":[{\"key\":\"개인정보보호법|26|\",\"why\":\"위탁\"}],"
                    + "\"no_basis\":false,\"reason\":\"재생 손 출력\"}");
            default -> FakeProvider.ok("{\"answer\":\"재생 손 출력.\",\"cites\":[\"개인정보보호법|26|\"]}");
        };
    }

    /** 재생: 저장한 모델 출력. A1 자유 답이 받은 질문 사실을 안 내면 unanswered에 넣고, 원장 인용은 저장한 것 + 나머지 없음. */
    static ProviderResponse replay(ProviderRequest r) {
        JsonNode in = JSON.readTree(r.user().split("\n\n\\[다시 요청]")[0]);
        JsonNode c = current;
        return switch (r.promptId()) {
            case "A0_intake" -> FakeProvider.ok(c.get("A0_intake").toString());
            case "A1_facts" -> {
                if ("ANSWER".equals(in.get("source").asString())) {
                    String fact = in.get("question").get("fact").asString();
                    // 저장한 자유 답 출력은 그 질문 사실에 대한 것일 때만(질문 차례가 바뀌면 다른 사실에 답함)
                    boolean same = c.has("A1_answer") && c.get("A1_answer").get("facts").size() > 0
                            && fact.equals(c.get("A1_answer").get("facts").get(0).get("fact").asString())
                            && in.get("text").asString().contains(c.get("A1_answer").get("facts").get(0).get("quote").asString());
                    ObjectNode o = same ? (ObjectNode) c.get("A1_answer").deepCopy()
                            : (ObjectNode) JSON.readTree("{\"flows\":[],\"facts\":[]}");
                    boolean has = false;
                    for (JsonNode f : o.get("facts")) {
                        has |= fact.equals(f.get("fact").asString());
                    }
                    if (!has) {
                        o.putArray("unanswered").add(fact);
                    }
                    yield FakeProvider.ok(o.toString());
                }
                yield FakeProvider.ok(c.get("A1_inquiry").toString());
            }
            case "A3_ledger" -> {
                Map<String, JsonNode> stored = new LinkedHashMap<>();
                if (c.has("A3_ledger")) {
                    c.get("A3_ledger").get("cites").forEach(x -> stored.put(x.get("flow").asInt() + ":" + x.get("fact").asString(), x));
                }
                ObjectNode out = JSON.createObjectNode();
                ArrayNode cites = out.putArray("cites");
                for (JsonNode g : in.get("groups")) {
                    for (JsonNode fl : g.get("flows")) {
                        JsonNode s = stored.get(fl.asInt() + ":" + g.get("fact").asString());
                        if (s != null) {
                            cites.add(s);
                        } else {
                            ObjectNode n = cites.addObject();
                            n.put("flow", fl.asInt());
                            n.put("fact", g.get("fact").asString());
                            n.put("match", "없음");
                            n.putArray("rows");
                        }
                    }
                }
                yield FakeProvider.ok(out.toString());
            }
            case "A5_answer", "A5_general", "A5_general_answer" -> a5(r);   // S8b: 답은 질문 수와 무관, 손 출력
            default -> FakeProvider.ok("{\"flags\":[]}");   // 감시는 질문 수와 무관: 표시 없음으로(저장한 표시는 그때 쟁점 기준)
        };
    }

    /** 사안 하나를 끝까지: fixed가 있으면 그 답, 없으면 첫 보기 중 '잘 모르겠음'이 아닌 것. free = {at_question, text}. */
    static Map<String, Object> run(String content, String service, JsonNode free, Map<String, String> fixed) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", content);
        if (service != null) {
            body.put("service_name", service);
        }
        JsonNode r = post("/api/cases", body);
        List<String> asked = new ArrayList<>();
        int n = 0;
        while (r.has("outcome") && "QUESTION".equals(r.get("outcome").asString()) && n < 40) {
            n++;
            JsonNode q = r.get("question");
            String key = (q.get("flow").isNull() ? "공통" : q.get("flow").asString()) + ":" + q.get("fact_id").asString();
            asked.add(key + "#" + q.get("ask_no").asInt());
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("question_turn", q.get("turn_no").asInt());
            if (free != null && free.get("at_question").asInt() == n) {
                a.put("choices", List.of());
                String fid = q.get("fact_id").asString();
                a.put("text", free.has("by_fact") && free.get("by_fact").has(fid) ? free.get("by_fact").get(fid).asString()
                        : free.get("text").asString());
            } else {
                String c = fixed.get(key);
                String detail = null;
                if (c == null) {
                    for (JsonNode ch : q.get("choices")) {
                        if (!ch.asString().contains("모르겠")) {
                            c = ch.asString();
                            break;
                        }
                    }
                    if (c.startsWith("예(") || c.contains("(자유 입력")) {
                        detail = c.contains("나라") ? "미국" : c.contains("법령") ? "개인정보 보호법 제17조" : "기타";
                    }
                }
                a.put("choices", List.of(c));
                if (detail != null) {
                    a.put("detail", detail);
                }
            }
            r = post("/api/cases/" + r.get("case_id").asString() + "/answers", a);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("questions", asked.size());
        m.put("asked", asked);
        m.put("outcome", r.has("outcome") ? r.get("outcome").asString() : r.toString());
        JsonNode res = r.get("result");
        if (res != null && !res.isNull()) {
            m.put("grade", res.get("case_grade").asString(null));
            m.put("exit", res.get("exit_type").asString());
            List<String> reasons = new ArrayList<>();
            res.get("exit_reasons").forEach(x -> reasons.add(x.get("code").asString() + " " + x.get("detail").asString("")));
            m.put("reasons", reasons);
            Map<String, List<String>> issues = new LinkedHashMap<>();
            for (JsonNode f : res.get("flows")) {
                List<String> is = new ArrayList<>();
                f.get("issues").forEach(i -> is.add(i.get("name").asString() + "/" + i.get("grade").asString()));
                issues.put("흐름 " + f.get("flow").asInt(), is);
            }
            m.put("issues", issues);
            List<String> un = new ArrayList<>();
            if (res.hasNonNull("unasked")) {
                res.get("unasked").forEach(x -> un.add(x.asString()));
            }
            m.put("unasked", un);
        }
        return m;
    }

    static JsonNode post(String path, Object body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                .header("X-Requester-Id", "s7b-replay")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        return JSON.readTree(HTTP.send(req, HttpResponse.BodyHandlers.ofString()).body());
    }
}
