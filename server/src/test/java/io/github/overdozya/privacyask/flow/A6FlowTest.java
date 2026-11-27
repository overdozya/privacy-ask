package io.github.overdozya.privacyask.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.overdozya.privacyask.llm.FakeProvider;
import io.github.overdozya.privacyask.llm.ProviderRequest;
import io.github.overdozya.privacyask.rules.Condition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * S10 ③: A5 gap → A6(search_guides) → 다시 A5 → 이관 사유 빠짐, 사안당 2회 상한, A6 인용이 A7을 지남(즉답 근거로는 안 셈)·판정 인용 origin A6.
 * FlowApiTest의 서버·손 출력을 그대로 쓴다(같은 테스트 DB: 15 벡터 적재 파일이 없으므로 search_guides는 글자 검색만 = TEXT_ONLY).
 * 문의는 FlowApiTest의 낮은 등급 사안(우리가 지음). 평가셋 문장은 쓰지 않는다.
 */
class A6FlowTest {
    @BeforeAll
    static void start() throws Exception {
        FlowApiTest.start();
    }

    @AfterAll
    static void stop() {
        FlowApiTest.stop();
    }

    @BeforeEach
    void routes() {
        new FlowApiTest().exampleRoutes();
        String a1 = FlowApiTest.lowOnlyA1();
        FlowApiTest.routes.put("A1_facts", r -> FakeProvider.ok(a1));
    }

    static JsonNode input(ProviderRequest r) {
        return FlowApiTest.JSON.readTree(r.user().split("\n\n\\[다시 요청]")[0]);
    }

    static int results(JsonNode in) {
        return in.has("tools") ? in.get("tools").get("results").size() : -1;
    }

    /** 손 출력: 첫 쟁점에 해석 필요 gap을 두고 search_guides를 요청. */
    static String askTool(ProviderRequest r) {
        ObjectNode out = (ObjectNode) FlowApiTest.JSON.readTree(FlowApiTest.answerFromInput(r, true));
        JsonNode first = out.get("issues").get(0);
        ObjectNode g = ((ArrayNode) out.get("gaps")).addObject();
        g.put("kind", "INTERPRETATION");
        g.put("flow", first.get("flow").asInt());
        g.put("issue", first.get("issue").asString());
        g.putArray("facts");
        g.put("note", "손으로 쓴 해석 필요");
        ObjectNode q = out.putArray("tool_requests").addObject();
        q.put("tool", "search_guides");
        q.put("query", "개인정보 처리 위탁 문서 기재");
        q.put("flow", first.get("flow").asInt());
        q.put("issue", first.get("issue").asString());
        q.putNull("section_id");
        q.put("why", "손으로 쓴 까닭");
        return out.toString();
    }

    /** 낮은 등급 사안을 질문이 끝날 때까지('아니오' 쪽). */
    static FlowApiTest.Res runLow() throws Exception {
        FlowApiTest.Res r = FlowApiTest.create();
        String id = r.body().get("case_id").asString();
        for (int i = 0; i < 8 && r.status() == 200 && "QUESTION".equals(r.body().get("outcome").asString()); i++) {
            JsonNode q = r.body().get("question");
            List<String> ch = Condition.strings(q.get("choices"));
            String pick = ch.contains("아니오") ? "아니오" : ch.stream().filter(c -> c.startsWith("아니오")).findFirst()
                    .orElseGet(() -> ch.stream().filter(c -> !c.equals("잘 모르겠음")).findFirst().orElseThrow());
            r = FlowApiTest.post("/api/cases/" + id + "/answers", FlowApiTest.answer(q.get("turn_no").asInt(), pick));
        }
        return r;
    }

    static List<String> a6Steps(String id) {
        return FlowApiTest.jdbc.sql("SELECT CONCAT(actor,' ',name) FROM steps WHERE case_id=? AND step='A6' ORDER BY seq")
                .param(id).query(String.class).list();
    }

    /** gap → A6 → 다시 A5 → 해석 필요 사유 빠짐 → 즉답. 찾은 절을 인용 → A7 통과, '해석 참고'로만 셈, 판정 인용 origin A6. */
    @Test
    void gapResolvedByA6() throws Exception {
        List<String> found = new ArrayList<>();
        FlowApiTest.routes.put("A5_answer", r -> {
            JsonNode in = input(r);
            if (results(in) <= 0) {
                return FakeProvider.ok(askTool(r));
            }
            String key = in.get("tools").get("results").get(0).get("sections").get(0).get("key").asString();
            found.add(key);
            ObjectNode out = (ObjectNode) FlowApiTest.JSON.readTree(FlowApiTest.answerFromInput(r, true));
            ((ArrayNode) out.get("issues").get(0).get("cites")).add(key);
            return FakeProvider.ok(out.toString());
        });
        FlowApiTest.Res r = runLow();
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(200);
        String id = r.body().get("case_id").asString();
        JsonNode res = r.body().get("result");
        assertThat(FlowApiTest.outcomes(id, "A5_answer")).containsExactly("OK", "OK");
        assertThat(a6Steps(id)).containsExactly("TOOL " + TurnService.A6_SEARCH_NAME);
        assertThat(res.get("exit_reasons").toString()).doesNotContain("GAP_INTERPRETATION");
        assertThat(res.get("exit_type").asString()).isEqualTo("ANSWER");
        assertThat(res.get("answer").get("gaps")).isEmpty();
        // 찾은 절: 답 출처 A6·즉답 근거 아님, A7 검증 문제 없음, 해석 참고 1
        String key = found.get(0);
        JsonNode src = null;
        for (JsonNode s : res.get("answer").get("sources")) {
            if (s.get("key").asString().equals(key)) {
                src = s;
            }
        }
        assertThat(src).isNotNull();
        assertThat(src.get("origin").asString()).isEqualTo("A6");
        assertThat(src.get("answer_basis").asBoolean()).isFalse();
        assertThat(res.get("verify").get("problems")).isEmpty();
        assertThat(res.get("verify").get("reference").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(FlowApiTest.jdbc.sql("""
                SELECT c.origin FROM case_judgment_citations c JOIN case_judgments j ON j.judgment_id=c.judgment_id
                WHERE j.case_id=? AND c.cite_key=?""").params(id, key).query(String.class).list()).containsExactly("A6");
        // A6 단계 기록: 글자 검색만(테스트 DB에 벡터 없음), 절 3개와 해시
        Map<String, Object> st = FlowApiTest.jdbc.sql("SELECT input_json, output_json FROM steps WHERE case_id=? AND step='A6'")
                .param(id).query().singleRow();
        JsonNode out = FlowApiTest.JSON.readTree(String.valueOf(st.get("output_json")));
        assertThat(out.get("mode").asString()).isEqualTo("TEXT_ONLY");
        assertThat(out.get("sections")).hasSize(3);
        assertThat(FlowApiTest.JSON.readTree(String.valueOf(st.get("input_json"))).get("query").asString())
                .isEqualTo("개인정보 처리 위탁 문서 기재");
    }

    /**
     * S10b: A6 뒤 해석 필요 gap을 A6 절 인용 없이 빼면 업무 검사가 거절하고 이유를 붙여 1회 다시 부른다. 다시 시도에서도 그러면 LLM_FAILED가
     * 아니라 그 gap을 남긴 것으로 보고(코드가 되살림) 이관 사유를 유지한다.
     */
    @Test
    void droppedGapWithoutA6CiteKeepsReason() throws Exception {
        List<String> retries = new ArrayList<>();
        FlowApiTest.routes.put("A5_answer", r -> {
            if (results(input(r)) <= 0) {
                return FakeProvider.ok(askTool(r));
            }
            if (r.user().contains("[다시 요청]")) {
                retries.add(r.user().substring(r.user().indexOf("[다시 요청]")));
            }
            return FakeProvider.ok(FlowApiTest.answerFromInput(r, true));   // gap을 빼고 A6 절은 인용하지 않음(두 번 다)
        });
        FlowApiTest.Res r = runLow();
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(200);
        String id = r.body().get("case_id").asString();
        assertThat(FlowApiTest.outcomes(id, "A5_answer")).containsExactly("OK", "CHECK_FAIL", "OK");
        assertThat(retries).hasSize(1);
        assertThat(retries.get(0)).contains("tools.results 절 인용이 없음");
        assertThat(FlowApiTest.jdbc.sql("SELECT status FROM steps WHERE case_id=? AND step='A5'").param(id)
                .query(String.class).list()).containsExactly("RETRY_OK");
        JsonNode res = r.body().get("result");
        assertThat(res.get("exit_reasons").toString()).contains("GAP_INTERPRETATION");
        assertThat(res.get("exit_type").asString()).isEqualTo("TRANSFER");
        JsonNode gap = res.get("answer").get("gaps").get(0);
        assertThat(gap.get("kind").asString()).isEqualTo("INTERPRETATION");
        assertThat(gap.get("handled").asString()).isEqualTo("REASON");
        assertThat(gap.get("note").asString()).contains("코드가 남김");
    }

    /** 사안당 2회 상한: 모델이 계속 요청해도 도구는 2번, 세 번째 요청은 '도구 멈춤' 기록, 마지막 답의 gap → 이관 사유. */
    @Test
    void twoCallLimit() throws Exception {
        FlowApiTest.routes.put("A5_answer", r -> FakeProvider.ok(askTool(r)));
        FlowApiTest.Res r = runLow();
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(200);
        String id = r.body().get("case_id").asString();
        assertThat(FlowApiTest.outcomes(id, "A5_answer")).containsExactly("OK", "OK", "OK");
        assertThat(a6Steps(id)).containsExactly("TOOL " + TurnService.A6_SEARCH_NAME, "TOOL " + TurnService.A6_SEARCH_NAME,
                "TOOL " + TurnService.A6_STOP_NAME);
        JsonNode res = r.body().get("result");
        assertThat(res.get("exit_reasons").toString()).contains("GAP_INTERPRETATION");
        assertThat(res.get("exit_type").asString()).isEqualTo("TRANSFER");
        // 두 번째 검색은 첫 번째가 붙인 절을 빼고 찾는다(같은 절을 두 번 붙이지 않음)
        List<String> outs = FlowApiTest.jdbc.sql("SELECT output_json FROM steps WHERE case_id=? AND step='A6' AND name=? ORDER BY seq")
                .params(id, TurnService.A6_SEARCH_NAME).query(String.class).list();
        List<String> ids = new ArrayList<>();
        for (String o : outs) {
            FlowApiTest.JSON.readTree(o).get("sections").forEach(s -> ids.add(s.get("section_id").asString()));
        }
        assertThat(ids).doesNotHaveDuplicates().hasSize(6);
        // 마지막 A5 입력: 남은 수 0, 결과 2개
        String lastIn = FlowApiTest.jdbc.sql("SELECT input_json FROM steps WHERE case_id=? AND step='A5'").param(id)
                .query(String.class).single();
        JsonNode t = FlowApiTest.JSON.readTree(lastIn).get("tools");
        assertThat(t.get("remaining").asInt()).isZero();
        assertThat(t.get("results")).hasSize(2);
    }
}
