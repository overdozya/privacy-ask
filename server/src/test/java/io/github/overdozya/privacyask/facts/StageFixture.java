package io.github.overdozya.privacyask.facts;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.overdozya.privacyask.baseline.BaselineStep;
import io.github.overdozya.privacyask.common.AppClock;
import io.github.overdozya.privacyask.common.CodeVersion;
import io.github.overdozya.privacyask.intake.IntakeStep;
import io.github.overdozya.privacyask.knowledge.Knowledge;
import io.github.overdozya.privacyask.knowledge.KnowledgeSnapshot;
import io.github.overdozya.privacyask.knowledge.LoadedDb;
import io.github.overdozya.privacyask.llm.FakeProvider;
import io.github.overdozya.privacyask.llm.JdbcLlmCallLogWriter;
import io.github.overdozya.privacyask.llm.LlmGateway;
import io.github.overdozya.privacyask.llm.LlmProperties;
import io.github.overdozya.privacyask.llm.PromptCatalog;
import io.github.overdozya.privacyask.record.CaseInput.NewCase;
import io.github.overdozya.privacyask.record.CaseLock;
import io.github.overdozya.privacyask.record.CaseReader;
import io.github.overdozya.privacyask.record.CaseState;
import io.github.overdozya.privacyask.record.CaseStore;
import io.github.overdozya.privacyask.watch.OmissionWatchStep;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * S6 단계 테스트 바탕: 테스트 MySQL(LoadedDb, 적재까지 된 것) + 실제 프롬프트 파일 + FakeProvider(손으로 쓴 출력).
 * 운영과 같게 JSON은 SNAKE_CASE, 호출 기록은 llm_call_logs에 실제로 쓴다(단계 번호 잇기 확인용).
 */
public final class StageFixture {
    public static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

    public final JdbcClient jdbc = LoadedDb.jdbc();
    public final TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(LoadedDb.dataSource()));
    public final KnowledgeSnapshot snap = LoadedDb.snapshot();
    public final Knowledge knowledge = Knowledge.of(snap);
    public final PromptCatalog prompts = PromptCatalog.load("classpath:prompts", JSON);
    public final CaseStore store = new CaseStore(jdbc, tx, knowledge, JSON, prompts::has);
    public final CaseReader reader = new CaseReader(jdbc, JSON);
    public final CaseLock lock = new CaseLock(jdbc, tx);
    public final AppClock clock = AppClock.system();
    public final CodeVersion code = new CodeVersion("test");
    public final FakeProvider fake = new FakeProvider();
    public final LlmProperties props = new LlmProperties("fake", "test-model", new BigDecimal("0"), null,
            Duration.ofSeconds(20), Duration.ofMillis(1), false, new LlmProperties.Gemini("http://x", true, true));
    public final LlmGateway gateway = new LlmGateway(fake, prompts, new JdbcLlmCallLogWriter(jdbc), JSON, clock, props,
            d -> {});

    public final IntakeStep intake = new IntakeStep(gateway, store, lock, jdbc, clock, code);
    public final FactExtractStep facts = new FactExtractStep(gateway, store, lock, jdbc, tx, knowledge, clock, code);
    public final AnswerApplier answers = new AnswerApplier(store, lock, knowledge, clock, code);
    public final BaselineStep baseline = new BaselineStep(gateway, store, lock, jdbc, knowledge, clock, code);
    public final OmissionWatchStep watch = new OmissionWatchStep(gateway, store, lock, jdbc, knowledge, clock, code);

    public static boolean available() {
        return LoadedDb.available();
    }

    /** 새 사안(지금 판 셋). */
    public String newCase(String content, String serviceName) {
        String id = UUID.randomUUID().toString();
        store.addCase(new NewCase(id, "test-requester", content, serviceName, snap.catalog().ruleSetVersion(),
                snap.packages().currentVersion(), snap.ledger().latestVersion().orElseThrow(), clock.nowUtc()));
        return id;
    }

    public CaseState state(String caseId) {
        return reader.load(caseId).orElseThrow();
    }

    public Instant deadline() {
        return clock.now().plusSeconds(60);
    }

    public long count(String sql, Object... args) {
        return jdbc.sql(sql).params(args).query(Long.class).single();
    }

    /** 사안의 D9 행 수(단계·흐름·사실). 거부·되돌림 뒤 그대로인지 볼 때. */
    public String rows(String caseId) {
        return count("SELECT COUNT(*) FROM steps WHERE case_id=?", caseId) + "/"
                + count("SELECT COUNT(*) FROM flows WHERE case_id=?", caseId) + "/"
                + count("SELECT COUNT(*) FROM facts WHERE case_id=?", caseId);
    }
}
