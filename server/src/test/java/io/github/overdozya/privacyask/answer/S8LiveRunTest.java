package io.github.overdozya.privacyask.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.overdozya.privacyask.common.AppClock;
import io.github.overdozya.privacyask.flow.FlowException;
import io.github.overdozya.privacyask.flow.TurnResult;
import io.github.overdozya.privacyask.flow.TurnService;
import io.github.overdozya.privacyask.knowledge.Catalog;
import io.github.overdozya.privacyask.knowledge.Knowledge;
import io.github.overdozya.privacyask.llm.CallContext;
import io.github.overdozya.privacyask.llm.LlmCallLogWriter;
import io.github.overdozya.privacyask.llm.LlmGateway;
import io.github.overdozya.privacyask.llm.LlmProperties;
import io.github.overdozya.privacyask.llm.LlmProvider;
import io.github.overdozya.privacyask.llm.LlmResult;
import io.github.overdozya.privacyask.llm.PromptCatalog;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S8 ⑥ 실제 호출(S8_LIVE=1일 때만): 지은 문의(data/수집/S8_지은문의.json, 레포 밖)를 로컬 privacyask DB + server/.env 공급자로
 * 한 턴 흐름(TurnService = API 1·2와 같은 함수) 끝까지 돌린다. 답은 기계적(첫 보기 중 '잘 모르겠음'이 아닌 것).
 * 끝난 사안마다 A5 입력(steps.input_json 그대로)을 생각 설정만 바꾼 프롬프트로 한 번 더 보내 지연·토큰·출력을 견준다(버린 호출 discarded=1,
 * 사안 단계에는 안 남김). 결과 JSON은 S8_OUT 경로. .env 값은 스프링이 읽는다(이 코드는 읽거나 찍지 않는다).
 */
@EnabledIfEnvironmentVariable(named = "S8_LIVE", matches = "1")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.config.import=optional:file:./.env[.properties],classpath:code-version.properties")
class S8LiveRunTest {
    static final String WHO = "s8-live";
    @Autowired LlmProvider provider;
    @Autowired LlmCallLogWriter logs;
    @Autowired LlmProperties props;
    @Autowired JsonMapper json;
    @Autowired AppClock clock;
    @Autowired JdbcClient jdbc;
    @Autowired Knowledge knowledge;
    @Autowired TurnService turns;
    @Autowired LlmGateway gateway;

    @Test
    void run() throws Exception {
        assertThat(provider.name()).as("실제 공급자로만 돈다").isNotEqualTo("fake");
        Path file = Path.of(System.getenv().getOrDefault("S8_INQUIRIES", "../data/수집/S8_지은문의.json"));
        JsonNode spec = json.readTree(Files.readString(file));
        List<String> only = System.getenv("S8_ONLY") == null ? List.of() : List.of(System.getenv("S8_ONLY").split(","));
        boolean compare = !"0".equals(System.getenv("S8_COMPARE"));
        // S8_ALT_SAME=1(S8b 캐시 확인): 견주는 호출을 지금 프롬프트 그대로 보내고 첫 시도를 일부러 한 번 거절해 진짜 재시도(같은 글 + 끝에 거절 이유)를 만든다
        boolean same = "1".equals(System.getenv("S8_ALT_SAME"));
        List<String> altOnly = System.getenv("S8_ALT_ONLY") == null ? List.of() : List.of(System.getenv("S8_ALT_ONLY").split(","));
        PromptCatalog alt = same ? gateway.prompts() : PromptCatalog.load("file:" + flippedDir(), json);
        // 견주는 호출은 호출 상한을 S8_ALT_TIMEOUT(기본 90초)으로 늘려 실제 지연을 잰다(흐름의 20초 상한과 따로)
        Duration altTimeout = Duration.parse("PT" + System.getenv().getOrDefault("S8_ALT_TIMEOUT", "90") + "S");
        LlmProperties altProps = new LlmProperties(props.provider(), props.model(), props.temperature(), props.apiKey(),
                altTimeout, props.retryBackoff(), false, props.gemini());
        LlmGateway altGw = new LlmGateway(provider, alt, logs, json, clock, altProps, (Duration d) -> Thread.sleep(d));

        List<Map<String, Object>> runs = new ArrayList<>();
        for (JsonNode q : spec.get("inquiries")) {
            String id = q.get("id").asString();
            if (!only.isEmpty() && !only.contains(id)) {
                continue;
            }
            Map<String, Object> run = new LinkedHashMap<>();
            run.put("id", id);
            List<Map<String, Object>> turnRows = new ArrayList<>();
            TurnResult r = null;
            String caseId = null;
            try {
                String svc = q.hasNonNull("service_name") ? q.get("service_name").asString() : null;
                r = turns.create(WHO, q.get("content").asString(), svc);
                caseId = r.caseId();
                turnRows.add(turnRow("CREATE", r));
                for (int i = 0; i < 12 && TurnResult.QUESTION.equals(r.outcome()); i++) {
                    TurnResult.Question qq = r.question();
                    String choice = pick(qq);
                    String detail = detailFor(qq, choice);
                    r = turns.answer(WHO, caseId, qq.turnNo(), List.of(choice), detail, null);
                    Map<String, Object> tr = turnRow("ANSWER", r);
                    tr.put("q", (qq.flow() == null ? "공통" : qq.flow()) + ":" + qq.factId() + "#" + qq.askNo() + "=" + choice);
                    turnRows.add(tr);
                }
            } catch (FlowException e) {
                run.put("error", e.code() + " " + e.getMessage() + " " + e.details());
                caseId = caseId != null ? caseId : String.valueOf(e.details().get("case_id"));
            }
            run.put("case_id", caseId);
            run.put("turns", turnRows);
            if (r != null) {
                run.put("outcome", r.outcome());
                run.put("result", r.result());
            }
            run.put("calls", calls(caseId));
            if (compare && caseId != null && (altOnly.isEmpty() || altOnly.contains(id))) {
                rejectFirst[0] = same;
                run.put("alt", alt(caseId, altGw, alt, altTimeout));
            }
            runs.add(run);
            System.out.println("S8 live " + id + " " + run.get("outcome") + " " + run.getOrDefault("error", ""));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", props.model());
        out.put("temperature", props.temperature());
        out.put("prompt_versions", Map.of("files", versions(gateway.prompts()), "alt", versions(alt)));
        out.put("runs", runs);
        Path dst = Path.of(System.getenv().getOrDefault("S8_OUT", "build/s8_live.json"));
        Files.createDirectories(dst.toAbsolutePath().getParent());
        Files.writeString(dst, json.writerWithDefaultPrettyPrinter().writeValueAsString(out));
        System.out.println("S8 live 결과: " + dst.toAbsolutePath() + " 사안 " + runs.size());
    }

    static final boolean[] rejectFirst = new boolean[1];

    static Map<String, Object> turnRow(String kind, TurnResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("outcome", r.outcome());
        m.put("timing", r.timing());
        return m;
    }

    String pick(TurnResult.Question q) {
        for (String c : q.choices()) {
            if (!c.contains("모르") && !c.equals("모름")) {
                return c;
            }
        }
        return q.choices().get(0);
    }

    String detailFor(TurnResult.Question q, String choice) {
        Catalog.Fact f = knowledge.snapshot().catalog().fact(q.factId()).orElseThrow();
        boolean takes = f.option(choice).map(o -> "DETAIL".equals(o.kind())).orElse(false);
        if (!takes) {
            return null;
        }
        if ("F-S3".equals(q.factId())) {
            return "2026-12-20";
        }
        return "국내 업체";
    }

    List<Map<String, Object>> calls(String caseId) {
        if (caseId == null) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT call_id, prompt_id, prompt_version, attempt_no, outcome, latency_ms, prompt_tokens, output_tokens,
                       thought_tokens, cached_tokens, finish_reason, discarded, step_seq FROM llm_call_logs WHERE case_id=?
                ORDER BY call_id""")
                .param(caseId).query().listOfRows();
    }

    /** 끝난 사안의 A5 입력을 생각 설정만 바꾼 판으로 다시 보낸다(검사는 입력 JSON에서: 쟁점 쌍·키·행). */
    Map<String, Object> alt(String caseId, LlmGateway altGw, PromptCatalog alt, Duration altTimeout) {
        List<Map<String, Object>> steps = jdbc.sql("""
                SELECT prompt_id, CAST(input_json AS CHAR) AS input FROM steps WHERE case_id=? AND step='A5'
                  AND actor='LLM' ORDER BY seq DESC LIMIT 1""").param(caseId).query().listOfRows();
        if (steps.isEmpty()) {
            return Map.of("skipped", "A5 단계 없음");
        }
        String promptId = (String) steps.get(0).get("prompt_id");
        JsonNode in = json.readTree((String) steps.get(0).get("input"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("prompt_id", promptId);
        out.put("prompt_version", alt.get(promptId).versionTag());
        LlmResult<?> r;
        long t0 = System.nanoTime();
        if ("A5_answer".equals(promptId)) {
            r = altGw.call(promptId, in, AnswerOutput.class, o -> {
                        if (rejectFirst[0]) {
                            rejectFirst[0] = false;
                            return "캐시 확인용으로 첫 시도를 한 번 거절함(같은 답을 다시 내면 된다)";
                        }
                        return check(in, o);
                    },
                    new CallContext(null, null, clock.now().plus(altTimeout.multipliedBy(2)), true));
        } else {
            // 일반 입력은 단계 기록에 목록 글 대신 판·크기만 있으므로 같은 날의 목록으로 다시 짓는다
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("text", in.get("text").asString());
            m.put("articles", new io.github.overdozya.privacyask.packages.ArticleList(knowledge.snapshot(),
                    clock.seoulToday()).text());
            r = altGw.call(promptId, m, GeneralPicks.class, o -> null,
                    new CallContext(null, null, clock.now().plus(altTimeout.multipliedBy(2)), true));
        }
        out.put("wall_ms", (System.nanoTime() - t0) / 1_000_000);
        out.put("status", r.status().name());
        out.put("value", r.value());
        out.put("error", r.lastError());
        out.put("calls", jdbc.sql("""
                SELECT call_id, attempt_no, outcome, latency_ms, prompt_tokens, output_tokens, thought_tokens, cached_tokens,
                       finish_reason FROM llm_call_logs WHERE call_id IN (:ids) ORDER BY call_id""").param("ids", r.callIds()).query().listOfRows());
        return out;
    }

    static String check(JsonNode in, AnswerOutput o) {
        Set<String> keys = new LinkedHashSet<>(), rows = new LinkedHashSet<>(), pairs = new LinkedHashSet<>();
        in.get("sources").forEach(s -> keys.add(s.get("key").asString()));
        in.get("ledger_rows").forEach(s -> rows.add(s.get("row_id").asString()));
        in.get("flows").forEach(f -> f.get("issues").forEach(i -> pairs.add(f.get("flow").asInt() + "|" + i.get("issue").asString())));
        List<String> bad = new ArrayList<>();
        Set<String> got = new LinkedHashSet<>();
        for (AnswerOutput.IssueOut x : o.issues()) {
            if (!got.add(x.flow() + "|" + x.issue())) {
                bad.add("두 번 " + x.issue());
            }
            x.cites().stream().filter(c -> !keys.contains(c)).forEach(c -> bad.add("꾸러미 밖 " + c));
            x.ledgerRows().stream().filter(c -> !rows.contains(c)).forEach(c -> bad.add("후보 밖 " + c));
        }
        if (!got.equals(pairs)) {
            bad.add("쟁점 쌍이 다름 " + got + " / " + pairs);
        }
        return bad.isEmpty() ? null : String.join("; ", bad);
    }

    Map<String, String> versions(PromptCatalog c) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String id : List.of("A0_intake", "A1_facts", "A3_ledger", "A3_watch", "A5_answer", "A5_general",
                "A5_general_answer")) {
            m.put(id, c.get(id).versionTag());
        }
        return m;
    }

    /** A5 두 프롬프트만 머리 thinking을 뒤집은 판(default ↔ low). 나머지는 그대로. */
    static Path flippedDir() throws IOException {
        Path dir = Files.createTempDirectory("s8-prompts-alt");
        for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath:prompts/*")) {
            String text;
            try (InputStream in = r.getInputStream()) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (r.getFilename().startsWith("A5_") && r.getFilename().endsWith(".md")) {
                text = text.contains("thinking: default") ? text.replaceFirst("thinking: default", "thinking: low")
                        : text.replaceFirst("thinking: low", "thinking: default");
            }
            Files.writeString(dir.resolve(r.getFilename()), text);
        }
        return dir;
    }
}
