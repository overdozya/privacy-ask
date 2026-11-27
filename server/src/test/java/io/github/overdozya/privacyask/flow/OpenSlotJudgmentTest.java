package io.github.overdozya.privacyask.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.overdozya.privacyask.PrivacyAskApplication;
import io.github.overdozya.privacyask.issues.ExampleCaseDb;
import io.github.overdozya.privacyask.knowledge.LoadedDb;
import io.github.overdozya.privacyask.llm.FakeProvider;
import io.github.overdozya.privacyask.llm.LlmProvider;
import tools.jackson.databind.JsonNode;

/**
 * S9 ④: 판정 때 OPEN이 남은 슬롯 → 판정 행에 UNKNOWN(막은 사실을 미확인 칸에, C16 'OPEN에서 옴'), 출구 사유 OPEN_UNKNOWN, flags.open_slots.
 * 질문 상한을 1로 둔 서버로 예시 사안을 돌린다(묻지 않은 사실이 슬롯을 막게). 예시 사안은 우리가 지은 것.
 */
class OpenSlotJudgmentTest {
    static ConfigurableApplicationContext ctx;
    static HttpClient http;
    static String base;
    static JdbcClient jdbc;

    @BeforeAll
    static void start() throws Exception {
        assumeThat(LoadedDb.available() && ExampleCaseDb.available() && Files.isRegularFile(FlowApiTest.EXAMPLE))
                .as("적재 파일·Docker·case.db·예시사안.json 없음").isTrue();
        LoadedDb.dataSource();
        ctx = new SpringApplicationBuilder(PrivacyAskApplication.class)
                .run("--server.port=0",
                        "--spring.datasource.url=" + LoadedDb.url(LoadedDb.container().getDatabaseName()),
                        "--spring.datasource.username=" + LoadedDb.container().getUsername(),
                        "--spring.datasource.password=" + LoadedDb.container().getPassword(),
                        "--app.llm.provider=fake", "--app.data-dir=" + LoadedDb.DATA, "--app.question.max-per-case=1");
        JsonNode ex = FlowApiTest.JSON.readTree(Files.readString(FlowApiTest.EXAMPLE));
        String a1 = FlowApiTest.a1FromExample(ex.get("events").get(4).get("output"));
        FakeProvider fake = (FakeProvider) ctx.getBean(LlmProvider.class);
        fake.fallback(r -> switch (r.promptId()) {
            case "A0_intake" -> FakeProvider.ok("{\"signals\":[{\"kind\":\"OWN_CASE\",\"quote\":\"T 멤버십 회원에게\"},"
                    + "{\"kind\":\"ACTION\",\"quote\":\"제휴 카페 브랜드에 넘겨서\"}]}");
            case "A1_facts" -> FakeProvider.ok(a1);
            case "A3_ledger" -> FlowApiTest.ledgerCites(r);
            case "A3_watch" -> FakeProvider.ok("{\"flags\":[]}");
            case "A5_answer" -> FakeProvider.ok(FlowApiTest.answerFromInput(r, true));
            case "A7_summary" -> FakeProvider.ok(FlowApiTest.SUMMARY);
            default -> FakeProvider.ok("{}");
        });
        base = "http://127.0.0.1:" + ctx.getEnvironment().getProperty("local.server.port");
        http = HttpClient.newHttpClient();
        jdbc = LoadedDb.jdbc();
        FlowApiTest.content = ex.get("events").get(0).get("content").asString();
    }

    @AfterAll
    static void stop() {
        if (ctx != null) {
            ctx.close();
        }
    }

    static JsonNode post(String path, Object body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                .header("X-Requester-Id", "s9-open").POST(HttpRequest.BodyPublishers.ofString(
                        FlowApiTest.JSON.writeValueAsString(body))).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return FlowApiTest.JSON.readTree(res.body());
    }

    @Test
    void openSlotStoredAsUnknown() throws Exception {
        JsonNode r = post("/api/cases", Map.of("content", FlowApiTest.content));
        String id = r.get("case_id").asString();
        for (int i = 0; i < 6 && "QUESTION".equals(r.get("outcome").asString()); i++) {
            JsonNode q = r.get("question");
            String pick = FlowApiTest.EXAMPLE_ANSWERS.getOrDefault(FlowApiTest.key(q), "아니오");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("question_turn", q.get("turn_no").asInt());
            body.put("choices", List.of(pick));
            r = post("/api/cases/" + id + "/answers", body);
        }
        assertThat(r.get("outcome").asString()).isEqualTo("RESULT");
        JsonNode res = r.get("result");
        System.out.println("S9 OPEN 사안 사유: " + res.get("exit_reasons") + " unasked " + res.get("unasked"));
        // S9b: 남은 OPEN은 A2 멈춤 규칙상 등급·출구를 못 바꿈 → 사유 아님, '묻지 않은 사실(결과에 영향 없음)'
        assertThat(res.get("exit_reasons").toString()).doesNotContain("OPEN_UNKNOWN");
        assertThat(res.get("unasked_no_effect")).isNotEmpty();
        assertThat(r.get("state").asString()).isEqualTo("TRANSFERRED");
        long jid = res.get("judgment_id").asLong();
        JsonNode flags = FlowApiTest.JSON.readTree(jdbc.sql("SELECT flags FROM case_judgments WHERE judgment_id=?")
                .param(jid).query(String.class).single());
        assertThat(flags.get("open_slots")).isNotEmpty();
        for (JsonNode o : flags.get("open_slots")) {
            Map<String, Object> row = jdbc.sql("SELECT status, unknown_facts FROM case_judgment_slots WHERE judgment_id=? "
                    + "AND flow_no=? AND slot_key=?").params(jid, o.get("flow").asInt(), o.get("slot").asString()).query()
                    .singleRow();
            assertThat(row.get("status")).isEqualTo("UNKNOWN");
            assertThat(o.get("affects_exit").asBoolean()).isFalse();
            List<String> unknown = io.github.overdozya.privacyask.rules.Condition.strings(
                    FlowApiTest.JSON.readTree(String.valueOf(row.get("unknown_facts"))));
            o.get("blocking").forEach(b -> assertThat(unknown).contains(b.asString()));
            System.out.println("S9 OPEN → UNKNOWN: 흐름 " + o.get("flow") + " " + o.get("slot").asString() + " 막은 사실 "
                    + o.get("blocking") + " → unknown_facts " + unknown);
        }
        // 묶음의 남은 질문에 막은 사실이 들어감
        JsonNode bundle = FlowApiTest.JSON.readTree(jdbc.sql("SELECT review_bundle FROM case_judgments WHERE judgment_id=?")
                .param(jid).query(String.class).single());
        assertThat(bundle.get("remaining_questions").toString()).contains("결과에 영향 없음");
        assertThat(bundle.get("unasked_no_effect")).isNotEmpty();
    }
}
