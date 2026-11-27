package io.github.overdozya.privacyask.facts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.overdozya.privacyask.baseline.BaselineStep;
import io.github.overdozya.privacyask.common.AppClock;
import io.github.overdozya.privacyask.common.CodeVersion;
import io.github.overdozya.privacyask.intake.IntakeStep;
import io.github.overdozya.privacyask.knowledge.Knowledge;
import io.github.overdozya.privacyask.llm.LlmCallLogWriter;
import io.github.overdozya.privacyask.llm.LlmGateway;
import io.github.overdozya.privacyask.llm.LlmProperties;
import io.github.overdozya.privacyask.llm.LlmProvider;
import io.github.overdozya.privacyask.llm.PromptCatalog;
import io.github.overdozya.privacyask.record.CaseInput.NewCase;
import io.github.overdozya.privacyask.record.CaseLock;
import io.github.overdozya.privacyask.record.CaseReader;
import io.github.overdozya.privacyask.record.CaseState;
import io.github.overdozya.privacyask.record.CaseStore;
import io.github.overdozya.privacyask.watch.OmissionWatchStep;
import io.github.overdozya.privacyask.watch.OmissionWatchStep.FlowIssues;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 06 7절 S6 ③ 실제 호출: 지은 문의(data/수집/S6_지은문의.json, 레포 밖)를 로컬 privacyask DB + server/.env 공급자 설정으로
 * A0 → (구체면) A1 → A3 기준선 → 누락 감시까지 돌린다. 생각 설정 두 가지(프롬프트 머리 thinking: default / S6_THINKING, 기본 low)를
 * 문의마다 번갈아 돌려 지연·토큰·결과를 견준다. 결과 JSON은 S6_OUT 경로에. 평소 테스트에서는 돌지 않는다(S6_LIVE=1일 때만).
 * .env는 스프링이 읽는다(이 코드는 값을 읽거나 찍지 않는다).
 */
@EnabledIfEnvironmentVariable(named = "S6_LIVE", matches = "1")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.config.import=optional:file:./.env[.properties],classpath:code-version.properties")
class S6LiveRunTest {
    @Autowired LlmProvider provider;
    @Autowired LlmCallLogWriter logs;
    @Autowired LlmProperties props;
    @Autowired JsonMapper json;
    @Autowired AppClock clock;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager tm;
    @Autowired Knowledge knowledge;
    @Autowired CaseReader reader;
    @Autowired CaseLock lock;
    @Autowired CodeVersion code;

    record Variant(String name, PromptCatalog prompts, IntakeStep intake, FactExtractStep facts, BaselineStep baseline,
                   OmissionWatchStep watch) {}

    @Test
    void run() throws Exception {
        assertThat(provider.name()).as("실제 공급자로만 돈다").isNotEqualTo("fake");
        Path file = Path.of(System.getenv().getOrDefault("S6_INQUIRIES", "../data/수집/S6_지은문의.json"));
        JsonNode spec = json.readTree(Files.readString(file));
        String low = System.getenv().getOrDefault("S6_THINKING", "low");

        PromptCatalog def = PromptCatalog.load("classpath:prompts", json);
        PromptCatalog alt = PromptCatalog.load("file:" + variantDir(low) , json);
        CaseStore store = new CaseStore(jdbc, new TransactionTemplate(tm), knowledge, json,
                (id, v) -> def.has(id, v) || alt.has(id, v));
        List<Variant> variants = List.of(variant("default", def, store), variant(low, alt, store));

        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode q : spec.get("inquiries")) {
            for (Variant v : variants) {
                results.add(runOne(v, store, q));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", props.model());
        out.put("temperature", props.temperature());
        out.put("prompt_versions", Map.of("default", versions(def), low, versions(alt)));
        out.put("runs", results);
        Path dst = Path.of(System.getenv().getOrDefault("S6_OUT", "build/s6_live.json"));
        Files.createDirectories(dst.toAbsolutePath().getParent());
        Files.writeString(dst, json.writerWithDefaultPrettyPrinter().writeValueAsString(out));
        System.out.println("S6 live 결과: " + dst.toAbsolutePath() + " 사안 " + results.size());
    }

    private Map<String, String> versions(PromptCatalog c) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String id : List.of("A0_intake", "A1_facts", "A3_ledger", "A3_watch")) {
            m.put(id, c.get(id).versionTag());
        }
        return m;
    }

    private Variant variant(String name, PromptCatalog prompts, CaseStore store) {
        LlmGateway gw = new LlmGateway(provider, prompts, logs, json, clock, props, (Duration d) -> Thread.sleep(d));
        TransactionTemplate tx = new TransactionTemplate(tm);
        return new Variant(name, prompts, new IntakeStep(gw, store, lock, jdbc, clock, code),
                new FactExtractStep(gw, store, lock, jdbc, tx, knowledge, clock, code),
                new BaselineStep(gw, store, lock, jdbc, knowledge, clock, code),
                new OmissionWatchStep(gw, store, lock, jdbc, knowledge, clock, code));
    }

    /** 프롬프트 파일을 복사해 머리의 thinking만 바꾼 판(해시가 달라져 호출 기록에서 가려진다). */
    private static Path variantDir(String thinking) throws IOException {
        Path dir = Files.createTempDirectory("s6-prompts-" + thinking);
        for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath:prompts/*")) {
            String text;
            try (InputStream in = r.getInputStream()) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (r.getFilename().endsWith(".md") && !r.getFilename().startsWith("_")) {
                text = text.replaceFirst("thinking: default", "thinking: " + thinking);
            }
            Files.writeString(dir.resolve(r.getFilename()), text);
        }
        return dir;
    }

    private Map<String, Object> runOne(Variant v, CaseStore store, JsonNode q) {
        var snap = knowledge.snapshot();
        String id = UUID.randomUUID().toString();
        store.addCase(new NewCase(id, "s6-live", q.get("content").asString(), null, snap.catalog().ruleSetVersion(),
                snap.packages().currentVersion(), snap.ledger().latestVersion().orElseThrow(), clock.nowUtc()));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("inquiry", q.get("id").asString());
        r.put("variant", v.name());
        r.put("case_id", id);
        Map<String, Long> wall = new LinkedHashMap<>();
        r.put("wall_ms", wall);

        long t0 = System.nanoTime();
        IntakeStep.Result a0 = v.intake().run(reader.load(id).orElseThrow(), deadline());
        wall.put("A0", ms(t0));
        r.put("A0", a0.failed() ? Map.of("status", "LLM_FAILED", "error", a0.error())
                : Map.of("status", a0.status().name(), "intake_type", a0.decision().intakeType(),
                "out_of_scope_kind", String.valueOf(a0.decision().outOfScopeKind()),
                "reason", a0.decision().reason(), "signals", a0.signals().signals()));
        if (a0.failed() || !"SPECIFIC".equals(a0.decision().intakeType())) {
            return r;
        }
        t0 = System.nanoTime();
        FactExtractStep.Result a1 = v.facts().runInquiry(reader.load(id).orElseThrow(), deadline());
        wall.put("A1", ms(t0));
        CaseState st = reader.load(id).orElseThrow();
        r.put("A1", a1.failed() ? Map.of("status", "LLM_FAILED", "error", a1.error())
                : Map.of("status", a1.status().name(), "flows", st.flows(), "facts", factsView(st)));
        if (a1.failed()) {
            return r;
        }
        t0 = System.nanoTime();
        BaselineStep.Result a3 = v.baseline().run(st, deadline());
        wall.put("A3_ledger", ms(t0));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("status", a3.status().name());
        b.put("llm_called", a3.llmCalled());
        b.put("candidates", a3.candidates().groups().stream()
                .map(g -> g.factId() + "/" + g.service() + "/" + g.kind() + "/" + g.rows().size()).toList());
        b.put("verdicts", a3.verdicts());
        b.put("error", a3.error());
        r.put("A3_ledger", b);
        if (a3.failed()) {
            return r;
        }
        List<FlowIssues> found = new ArrayList<>();
        for (JsonNode fi : q.get("found_issues")) {
            List<String> issues = new ArrayList<>();
            fi.get("issues").forEach(x -> issues.add(x.asString()));
            found.add(new FlowIssues(fi.get("flow").asInt(), issues));
        }
        t0 = System.nanoTime();
        OmissionWatchStep.Result w = v.watch().run(reader.load(id).orElseThrow(), found, deadline());
        wall.put("A3_watch", ms(t0));
        r.put("A3_watch", w.failed() ? Map.of("status", "LLM_FAILED", "error", w.error())
                : Map.of("status", w.status().name(), "watch_flag", w.watchFlag(), "flags", w.flags().flags()));
        return r;
    }

    private static List<String> factsView(CaseState st) {
        return st.currentFacts().stream().map(f -> (f.flowNo() == null ? "-" : f.flowNo()) + ":" + f.factId() + "="
                + f.values().stream().map(x -> x.detail() == null ? x.label() : x.label() + "[" + x.detail() + "]").toList()
                + "/" + f.status()).toList();
    }

    private Instant deadline() {
        return clock.now().plusSeconds(60);
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
