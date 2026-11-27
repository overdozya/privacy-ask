package io.github.overdozya.privacyask.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.overdozya.privacyask.answer.Answer;
import io.github.overdozya.privacyask.knowledge.Catalog;
import io.github.overdozya.privacyask.knowledge.Knowledge;
import io.github.overdozya.privacyask.llm.LlmProperties;
import io.github.overdozya.privacyask.llm.LlmProvider;
import io.github.overdozya.privacyask.tools.GuideTools;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S10 실제 호출(S10_LIVE=1일 때만): 지은 문의(기본 data/수집/S8_지은문의.json의 구체 문의, S10_ONLY로 고름)를 로컬 privacyask DB(15 벡터 적재)
 * + server/.env 공급자로 한 턴 흐름 끝까지 돌린다(A6 켬, 입력 줄이기 끔 = 지금 기본). 그다음 닫힌 사안의 같은 상태로 A5(+A6) 라운드를
 * 입력 줄이기 끔·켬 두 번 저장 없이 다시 돌려(호출은 discarded) A5 입력 토큰·지연·인용 키·gap·최종 출구를 견준다.
 * 결과는 S10_OUT(기본 ../data/out/emb/s10_live.json, git 밖). .env 값은 스프링이 읽는다(이 코드는 읽거나 찍지 않는다).
 */
@EnabledIfEnvironmentVariable(named = "S10_LIVE", matches = "1")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.config.import=optional:file:./.env[.properties],classpath:code-version.properties")
class S10LiveRunTest {
    static final String WHO = "s10-live";
    @Autowired LlmProvider provider;
    @Autowired LlmProperties props;
    @Autowired JsonMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired Knowledge knowledge;
    @Autowired TurnService turns;
    @Autowired GuideTools tools;

    @Test
    void run() throws Exception {
        assertThat(provider.name()).as("실제 공급자로만 돈다").isNotEqualTo("fake");
        assertThat(tools.enabled()).isTrue();
        Path file = Path.of(System.getenv().getOrDefault("S10_INQUIRIES", "../data/수집/S8_지은문의.json"));
        JsonNode spec = json.readTree(Files.readString(file));
        List<String> only = System.getenv("S10_ONLY") == null ? List.of() : List.of(System.getenv("S10_ONLY").split(","));
        boolean probe = !"0".equals(System.getenv("S10_PROBE"));
        List<Map<String, Object>> runs = new ArrayList<>();
        // S10_CASES=사안ID,…: 새로 돌리지 않고 이미 닫힌 사안을 견주기만(프롬프트를 고친 뒤 같은 상태로 다시 잴 때)
        List<String> cases = System.getenv("S10_CASES") == null ? List.of() : List.of(System.getenv("S10_CASES").split(","));
        for (String cid : cases) {
            Map<String, Object> run = new LinkedHashMap<>();
            run.put("case_id", cid);
            run.put("probe_off", probe(cid, false));
            run.put("probe_on", probe(cid, true));
            runs.add(run);
        }
        for (JsonNode q : spec.get("inquiries")) {
            String id = q.get("id").asString();
            if (!cases.isEmpty() || !"SPECIFIC".equals(q.path("kind").asString("")) || (!only.isEmpty() && !only.contains(id))) {
                continue;
            }
            Map<String, Object> run = new LinkedHashMap<>();
            run.put("id", id);
            TurnResult r = null;
            String caseId = null;
            List<Object> timing = new ArrayList<>();
            try {
                String svc = q.hasNonNull("service_name") ? q.get("service_name").asString() : null;
                r = turns.create(WHO, q.get("content").asString(), svc);
                caseId = r.caseId();
                timing.add(r.timing());
                for (int i = 0; i < 12 && TurnResult.QUESTION.equals(r.outcome()); i++) {
                    TurnResult.Question qq = r.question();
                    String choice = pick(qq);
                    r = turns.answer(WHO, caseId, qq.turnNo(), List.of(choice), detailFor(qq, choice), null);
                    timing.add(r.timing());
                }
            } catch (FlowException e) {
                run.put("error", e.code() + " " + e.getMessage() + " " + e.details());
                caseId = caseId != null ? caseId : String.valueOf(e.details().get("case_id"));
            }
            run.put("case_id", caseId);
            run.put("last_turn_timing", timing.isEmpty() ? null : timing.get(timing.size() - 1));
            if (r != null && r.result() != null) {
                run.put("exit", r.result().exitType());
                run.put("reasons", r.result().exitReasons().stream().map(TurnResult.Reason::code).toList());
            }
            run.put("main", main(caseId));
            if (probe && caseId != null && r != null && r.result() != null) {
                run.put("probe_off", probe(caseId, false));
                run.put("probe_on", probe(caseId, true));
            }
            runs.add(run);
            System.out.println("S10 live " + id + " " + run.get("exit") + " " + run.get("reasons") + " " + run.getOrDefault("error", ""));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", props.model());
        out.put("temperature", props.temperature());
        out.put("index_vectors", tools.index().vectors());
        out.put("embedder", tools.embedder().available() ? "ONNX" : tools.embedder().why());
        out.put("runs", runs);
        Path dst = Path.of(System.getenv().getOrDefault("S10_OUT", "../data/out/emb/s10_live.json"));
        Files.createDirectories(dst.toAbsolutePath().getParent());
        Files.writeString(dst, json.writerWithDefaultPrettyPrinter().writeValueAsString(out));
        System.out.println("S10 live 결과: " + dst.toAbsolutePath() + " 사안 " + runs.size());
    }

    /** 본 실행: A5 호출(라운드)·A6 단계·첫 라운드와 마지막 답의 해석 필요·꾸러미 밖 gap. */
    Map<String, Object> main(String caseId) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (caseId == null) {
            return m;
        }
        List<Map<String, Object>> calls = jdbc.sql("""
                SELECT call_id, attempt_no, outcome, latency_ms, prompt_tokens, output_tokens, thought_tokens, cached_tokens,
                       response_text FROM llm_call_logs WHERE case_id=? AND prompt_id='A5_answer' AND discarded=0 ORDER BY call_id""")
                .param(caseId).query().listOfRows();
        List<Map<String, Object>> slim = new ArrayList<>();
        List<String> firstGaps = null;
        for (Map<String, Object> c : calls) {
            Map<String, Object> x = new LinkedHashMap<>(c);
            Object txt = x.remove("response_text");
            JsonNode o = txt == null ? null : safe(String.valueOf(txt));
            if (o != null) {
                x.put("gaps", kinds(o.get("gaps")));
                x.put("tool_requests", o.path("tool_requests"));
                if (firstGaps == null && "OK".equals(c.get("outcome"))) {
                    firstGaps = kinds(o.get("gaps"));
                }
            }
            slim.add(x);
        }
        m.put("a5_calls", slim);
        m.put("a6_steps", jdbc.sql("SELECT name, input_json, output_json FROM steps WHERE case_id=? AND step='A6' ORDER BY seq")
                .param(caseId).query().listOfRows().stream().map(r -> {
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("name", r.get("name"));
                    s.put("input", safe(String.valueOf(r.get("input_json"))));
                    s.put("output", safe(String.valueOf(r.get("output_json"))));
                    return s;
                }).toList());
        String a5 = jdbc.sql("SELECT output_json FROM steps WHERE case_id=? AND step='A5' AND status<>'LLM_FAILED' ORDER BY seq DESC LIMIT 1")
                .param(caseId).query(String.class).optional().orElse(null);
        List<String> finalGaps = List.of();
        if (a5 != null) {
            JsonNode o = json.readTree(a5);
            finalGaps = kinds(o.get("gaps"));
            m.put("cites", cites(o));
            m.put("a6_sources", o.path("sources").valueStream().filter(s -> "A6".equals(s.path("origin").asString(""))).map(s -> s.get("key").asString()).toList());
        }
        m.put("first_round_gaps", firstGaps);
        m.put("final_gaps", finalGaps);
        m.put("resolved_by_a6", firstGaps == null ? 0 : Math.max(0, interp(firstGaps) - interp(finalGaps)));
        return m;
    }

    Map<String, Object> probe(String caseId, boolean reduce) {
        long t = System.nanoTime();
        TurnService.Probe p = turns.probeA5(caseId, reduce, Duration.ofSeconds(60));
        long ms = (System.nanoTime() - t) / 1_000_000;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reduce", reduce);
        m.put("wall_ms", ms);
        m.put("rounds", p.rounds());
        m.put("tools", p.tools());
        List<Map<String, Object>> calls = p.callIds().isEmpty() ? List.of() : jdbc.sql("""
                SELECT call_id, attempt_no, outcome, latency_ms, prompt_tokens, output_tokens, thought_tokens, cached_tokens
                FROM llm_call_logs WHERE call_id IN (:ids) ORDER BY call_id""").param("ids", p.callIds()).query().listOfRows();
        m.put("calls", calls);
        m.put("prompt_tokens", calls.stream().mapToLong(c -> num(c.get("prompt_tokens"))).sum());
        m.put("latency_ms", calls.stream().mapToLong(c -> num(c.get("latency_ms"))).sum());
        if (p.answer() != null) {
            Answer a = p.answer();
            m.put("cites", a.issues().stream().map(i -> i.flow() + "|" + i.issue() + " " + i.cites()).toList());
            m.put("gaps", a.gaps().stream().map(g -> g.kind() + "→" + g.handled()).toList());
            m.put("a6_sources", a.sources().stream().filter(Answer.Source::fromA6).map(Answer.Source::key).toList());
            m.put("verify", p.report().summary());
            m.put("exit", p.exit().exitType());
            m.put("reasons", p.exit().reasons().stream().map(x -> x.code()).toList());
        }
        System.out.println("S10 probe " + caseId + " reduce=" + reduce + " tokens=" + m.get("prompt_tokens") + " ms="
                + m.get("latency_ms") + " rounds=" + p.rounds().size() + " exit=" + m.get("exit") + " " + m.get("reasons"));
        return m;
    }

    static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    JsonNode safe(String s) {
        try {
            return json.readTree(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static List<String> kinds(JsonNode gaps) {
        List<String> out = new ArrayList<>();
        if (gaps != null) {
            gaps.forEach(g -> out.add(g.path("kind").asString("")));
        }
        return out;
    }

    static int interp(List<String> kinds) {
        return (int) kinds.stream().filter(k -> "INTERPRETATION".equals(k) || "OUTSIDE_PACKAGE".equals(k)).count();
    }

    static List<String> cites(JsonNode a5) {
        List<String> out = new ArrayList<>();
        a5.path("issues").forEach(i -> out.add(i.path("flow").asInt() + "|" + i.path("issue").asString() + " " + i.path("cites")));
        return out;
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
