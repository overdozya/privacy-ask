package io.github.overdozya.privacyask.verify;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.overdozya.privacyask.flow.FlowException;
import io.github.overdozya.privacyask.flow.TurnResult;
import io.github.overdozya.privacyask.flow.TurnService;
import io.github.overdozya.privacyask.knowledge.Catalog;
import io.github.overdozya.privacyask.knowledge.Knowledge;
import io.github.overdozya.privacyask.llm.LlmProperties;
import io.github.overdozya.privacyask.llm.LlmProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S9 ⑤ 실제 호출(S9_LIVE=1일 때만): 지은 문의(기본 data/수집/S8_지은문의.json, 레포 밖, S9_ONLY로 고름)를 로컬 privacyask DB +
 * server/.env 공급자로 한 턴 흐름 끝까지(A7·A8까지) 돌린다. 답은 기계적(첫 보기 중 '모르'가 아닌 것). 최종 출구·사유·요지·검증,
 * 호출별 지연·토큰, 요청별 턴 시간을 S9_OUT(기본 build/s9_live.json)에. .env 값은 스프링이 읽는다(이 코드는 읽거나 찍지 않는다).
 */
@EnabledIfEnvironmentVariable(named = "S9_LIVE", matches = "1")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.config.import=optional:file:./.env[.properties],classpath:code-version.properties")
class S9LiveRunTest {
    static final String WHO = "s9-live";
    @Autowired LlmProvider provider;
    @Autowired LlmProperties props;
    @Autowired JsonMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired Knowledge knowledge;
    @Autowired TurnService turns;

    @Test
    void run() throws Exception {
        assertThat(provider.name()).as("실제 공급자로만 돈다").isNotEqualTo("fake");
        Path file = Path.of(System.getenv().getOrDefault("S9_INQUIRIES", "../data/수집/S8_지은문의.json"));
        JsonNode spec = json.readTree(Files.readString(file));
        List<String> only = System.getenv("S9_ONLY") == null ? List.of() : List.of(System.getenv("S9_ONLY").split(","));
        List<Map<String, Object>> runs = new ArrayList<>();
        for (JsonNode q : spec.get("inquiries")) {
            String id = q.get("id").asString();
            if (!only.isEmpty() && !only.contains(id)) {
                continue;
            }
            Map<String, Object> run = new LinkedHashMap<>();
            run.put("id", id);
            List<Map<String, Object>> rows = new ArrayList<>();
            TurnResult r = null;
            String caseId = null;
            try {
                String svc = q.hasNonNull("service_name") ? q.get("service_name").asString() : null;
                r = turns.create(WHO, q.get("content").asString(), svc);
                caseId = r.caseId();
                rows.add(row("CREATE", r, null));
                for (int i = 0; i < 12 && TurnResult.QUESTION.equals(r.outcome()); i++) {
                    TurnResult.Question qq = r.question();
                    String choice = pick(qq);
                    r = turns.answer(WHO, caseId, qq.turnNo(), List.of(choice), detailFor(qq, choice), null);
                    rows.add(row("ANSWER", r, (qq.flow() == null ? "공통" : qq.flow()) + ":" + qq.factId() + "=" + choice));
                }
            } catch (FlowException e) {
                run.put("error", e.code() + " " + e.getMessage() + " " + e.details());
                caseId = caseId != null ? caseId : String.valueOf(e.details().get("case_id"));
            }
            run.put("case_id", caseId);
            run.put("turns", rows);
            if (r != null) {
                run.put("outcome", r.outcome());
                run.put("state", r.state());
                run.put("result", r.result());
            }
            run.put("calls", caseId == null ? List.of() : jdbc.sql("""
                    SELECT call_id, prompt_id, prompt_version, attempt_no, outcome, latency_ms, prompt_tokens, output_tokens,
                           thought_tokens, cached_tokens, finish_reason, discarded, step_seq FROM llm_call_logs WHERE case_id=?
                    ORDER BY call_id""").param(caseId).query().listOfRows());
            runs.add(run);
            System.out.println("S9 live " + id + " " + run.get("outcome") + " " + run.get("state") + " "
                    + (r == null || r.result() == null ? "" : r.result().exitType() + " "
                    + r.result().exitReasons().stream().map(TurnResult.Reason::code).toList()) + " " + run.getOrDefault("error", ""));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", props.model());
        out.put("temperature", props.temperature());
        out.put("runs", runs);
        Path dst = Path.of(System.getenv().getOrDefault("S9_OUT", "build/s9_live.json"));
        Files.createDirectories(dst.toAbsolutePath().getParent());
        Files.writeString(dst, json.writerWithDefaultPrettyPrinter().writeValueAsString(out));
        System.out.println("S9 live 결과: " + dst.toAbsolutePath() + " 사안 " + runs.size());
    }

    static Map<String, Object> row(String kind, TurnResult r, String q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        if (q != null) {
            m.put("q", q);
        }
        m.put("outcome", r.outcome());
        m.put("timing", r.timing());
        return m;
    }

    String pick(TurnResult.Question q) {
        for (String c : q.choices()) {
            if (!c.contains("모르")) {
                return c;
            }
        }
        return q.choices().get(0);
    }

    String detailFor(TurnResult.Question q, String choice) {
        Catalog.Fact f = knowledge.snapshot().catalog().fact(q.factId()).orElseThrow();
        if (!f.option(choice).map(o -> "DETAIL".equals(o.kind())).orElse(false)) {
            return null;
        }
        return "F-S3".equals(q.factId()) ? "2026-12-20" : "국내 업체";
    }
}
