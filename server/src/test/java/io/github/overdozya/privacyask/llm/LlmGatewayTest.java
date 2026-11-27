package io.github.overdozya.privacyask.llm;

import static io.github.overdozya.privacyask.llm.CallLogEntry.Outcome.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.overdozya.privacyask.common.AppClock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 06 4.3 재시도 표와 4.4 기록을 FakeProvider로 확인한다(06 7절 S2 ①). */
class LlmGatewayTest {
    record Echo(String echo, Integer count) {}

    private final JsonMapper json = JsonMapper.builder().build();
    private final PromptCatalog prompts = PromptCatalog.load("classpath:prompts-test", json);
    private final LlmProperties props = new LlmProperties("fake", "test-model", new BigDecimal("0"), null,
            Duration.ofSeconds(20), Duration.ofSeconds(1), false, new LlmProperties.Gemini("http://x", true, true));
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-27T00:00:00Z"));
    private final List<Duration> sleeps = new ArrayList<>();
    private FakeProvider fake;
    private InMemoryCallLogs logs;
    private LlmGateway gw;

    @BeforeEach
    void setUp() {
        fake = new FakeProvider();
        logs = new InMemoryCallLogs();
        gw = new LlmGateway(fake, prompts, logs, json, new AppClock(clock), props, d -> {
            sleeps.add(d);
            clock.advance(d);
        });
    }

    private CallContext ctx() {
        return CallContext.forStep("case-1", 3, clock.instant().plusSeconds(60));
    }

    private LlmResult<Echo> call(LlmGateway.Check<Echo> check) {
        return gw.call("T_echo", Map.of("word", "a"), Echo.class, check, ctx());
    }

    @Test
    void okOnFirstAttempt() {
        fake.thenText("{\"echo\":\"a\",\"count\":2}");
        LlmResult<Echo> r = call(null);
        assertThat(r.status()).isEqualTo(LlmResult.Status.OK);
        assertThat(r.attempts()).isEqualTo(1);
        assertThat(r.value()).isEqualTo(new Echo("a", 2));
        assertThat(r.promptVersion()).matches(PromptCatalog.VERSION_TAG).startsWith("3@");
        assertThat(r.model()).isEqualTo("test-model");
        assertThat(r.temperature()).isEqualByComparingTo("0");
        assertThat(r.callIds()).hasSize(1);
        assertThat(logs.outcomes()).containsExactly(OK);
        CallLogEntry row = logs.rows.get(0);
        assertThat(row.caseId()).isEqualTo("case-1");
        assertThat(row.stepSeq()).isEqualTo(3);
        assertThat(row.promptId()).isEqualTo("T_echo");
        assertThat(row.provider()).isEqualTo("fake");
        assertThat(row.finishReason()).isEqualTo("STOP");
        assertThat(row.inputSha256()).hasSize(64);
        assertThat(row.responseText()).contains("echo");
        assertThat(row.attemptNo()).isEqualTo(1);
        // 요청: 공통 + 개별, 스키마·온도·최대 출력 토큰
        ProviderRequest req = fake.requests().get(0);
        assertThat(req.system()).startsWith("공통 시험 지시.").contains("word를 echo에");
        assertThat(req.schema().path("required").get(0).asString()).isEqualTo("echo");
        assertThat(req.maxOutputTokens()).isEqualTo(128);
        assertThat(req.timeout()).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void schemaFailThenRetryOkAppendsReason() {
        fake.thenText("말로 답함").thenText("{\"echo\":\"b\"}");
        LlmResult<Echo> r = call(null);
        assertThat(r.status()).isEqualTo(LlmResult.Status.RETRY_OK);
        assertThat(r.attempts()).isEqualTo(2);
        assertThat(logs.outcomes()).containsExactly(SCHEMA_FAIL, OK);
        assertThat(fake.requests().get(0).user()).doesNotContain("[다시 요청]");
        assertThat(fake.requests().get(1).user()).contains("[다시 요청]").contains("JSON이 아님");
        assertThat(sleeps).isEmpty();
    }

    @Test
    void validationFailsTwiceIsLlmFailed() {
        fake.thenText("{\"echo\":\"z\"}").thenText("{\"echo\":\"y\"}").thenText("{\"echo\":\"a\"}");
        LlmResult<Echo> r = call(null);
        assertThat(r.status()).isEqualTo(LlmResult.Status.LLM_FAILED);
        assertThat(r.ok()).isFalse();
        assertThat(r.attempts()).isEqualTo(2);
        assertThat(r.value()).isNull();
        assertThat(r.lastError()).startsWith("스키마 위반");
        assertThat(logs.outcomes()).containsExactly(SCHEMA_FAIL, SCHEMA_FAIL);
        assertThat(fake.requests()).hasSize(2);
    }

    @Test
    void businessCheckRejectsThenAccepts() {
        fake.thenText("{\"echo\":\"c\"}").thenText("{\"echo\":\"a\"}");
        LlmResult<Echo> r = call(e -> "a".equals(e.echo()) ? null : "word와 다름");
        assertThat(r.status()).isEqualTo(LlmResult.Status.RETRY_OK);
        assertThat(logs.outcomes()).containsExactly(CHECK_FAIL, OK);
        assertThat(logs.rows.get(0).error()).contains("word와 다름");
        assertThat(fake.requests().get(1).user()).contains("업무 검사 거절");
    }

    @Test
    void finishReasonNotStopCountsAsValidationFailure() {
        fake.thenResponse(FakeProvider.finish("{\"echo\":", "MAX_TOKENS")).thenText("{\"echo\":\"a\"}");
        LlmResult<Echo> r = call(null);
        assertThat(r.status()).isEqualTo(LlmResult.Status.RETRY_OK);
        assertThat(logs.outcomes()).containsExactly(FINISH_FAIL, OK);
        assertThat(logs.rows.get(0).finishReason()).isEqualTo("MAX_TOKENS");
    }

    @Test
    void transport503RetriedOnceWithBackoff() {
        fake.thenThrow(ProviderException.http(503, null, 40, "{\"error\":\"busy\"}")).thenText("{\"echo\":\"a\"}");
        LlmResult<Echo> r = call(null);
        assertThat(r.status()).isEqualTo(LlmResult.Status.RETRY_OK);
        assertThat(logs.outcomes()).containsExactly(HTTP_ERROR, OK);
        assertThat(logs.rows.get(0).httpStatus()).isEqualTo(503);
        assertThat(logs.rows.get(0).latencyMs()).isEqualTo(40);
        assertThat(sleeps).hasSize(1);
        assertThat(sleeps.get(0)).isBetween(Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Test
    void transportFailsTwiceIsLlmFailed() {
        fake.thenThrow(ProviderException.http(500, null, 5, null)).thenThrow(ProviderException.http(502, null, 5, null));
        LlmResult<Echo> r = call(null);
        assertThat(r.status()).isEqualTo(LlmResult.Status.LLM_FAILED);
        assertThat(r.attempts()).isEqualTo(2);
        assertThat(logs.outcomes()).containsExactly(HTTP_ERROR, HTTP_ERROR);
    }

    @Test
    void clientErrorIsNotRetried() {
        fake.thenThrow(ProviderException.http(401, null, 5, "{\"error\":\"key\"}")).thenText("{\"echo\":\"a\"}");
        LlmResult<Echo> r = call(null);
        assertThat(r.status()).isEqualTo(LlmResult.Status.LLM_FAILED);
        assertThat(r.attempts()).isEqualTo(1);
        assertThat(r.lastError()).isEqualTo("HTTP 401");
        assertThat(logs.outcomes()).containsExactly(HTTP_ERROR);
        assertThat(logs.rows.get(0).httpStatus()).isEqualTo(401);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void timeoutThenSchemaFailThenOkUsesAllThreeAttempts() {
        fake.thenThrow(ProviderException.timeout(20_000)).thenText("{\"echo\":1}").thenText("{\"echo\":\"a\"}");
        LlmResult<Echo> r = call(null);
        assertThat(r.status()).isEqualTo(LlmResult.Status.RETRY_OK);
        assertThat(r.attempts()).isEqualTo(3);
        assertThat(logs.outcomes()).containsExactly(TIMEOUT, SCHEMA_FAIL, OK);
        assertThat(logs.rows).extracting(CallLogEntry::attemptNo).containsExactly(1, 2, 3);
    }

    @Test
    void neverMoreThanThreeAttempts() {
        fake.thenText("x").thenThrow(ProviderException.http(503, null, 1, null)).thenText("y").thenText("{\"echo\":\"a\"}");
        LlmResult<Echo> r = call(null);
        assertThat(r.status()).isEqualTo(LlmResult.Status.LLM_FAILED);
        assertThat(r.attempts()).isEqualTo(3);
        assertThat(logs.outcomes()).containsExactly(SCHEMA_FAIL, HTTP_ERROR, SCHEMA_FAIL);
    }

    @Test
    void retryAfterBeyondDeadlineIsNotWaited() {
        fake.thenThrow(ProviderException.http(429, Duration.ofSeconds(120), 5, null)).thenText("{\"echo\":\"a\"}");
        LlmResult<Echo> r = call(null);
        assertThat(r.status()).isEqualTo(LlmResult.Status.LLM_FAILED);
        assertThat(r.attempts()).isEqualTo(1);
        assertThat(sleeps).isEmpty();
        assertThat(r.lastError()).contains("다시 할 시간 없음");
    }

    @Test
    void retryAfterWithinDeadlineIsHonoured() {
        fake.thenThrow(ProviderException.http(429, Duration.ofSeconds(3), 5, null)).thenText("{\"echo\":\"a\"}");
        LlmResult<Echo> r = call(null);
        assertThat(r.status()).isEqualTo(LlmResult.Status.RETRY_OK);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(3));
    }

    @Test
    void perAttemptTimeoutIsCappedByTurnDeadline() {
        fake.thenText("{\"echo\":\"a\"}");
        gw.call("T_echo", Map.of("word", "a"), Echo.class, null,
                CallContext.forStep("case-1", 1, clock.instant().plusSeconds(7)));
        assertThat(fake.requests().get(0).timeout()).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void passedDeadlineMakesNoCall() {
        LlmResult<Echo> r = gw.call("T_echo", Map.of("word", "a"), Echo.class, null,
                CallContext.standalone(clock.instant().minusSeconds(1)));
        assertThat(r.status()).isEqualTo(LlmResult.Status.LLM_FAILED);
        assertThat(fake.requests()).isEmpty();
        assertThat(logs.rows).isEmpty();
    }

    @Test
    void discardedAndStandaloneContextAreRecorded() {
        fake.thenText("{\"echo\":\"a\"}").thenText("{\"echo\":\"a\"}");
        gw.call("T_echo", Map.of("word", "a"), JsonNode.class, null, ctx().asDiscarded());
        gw.call("T_echo", Map.of("word", "a"), JsonNode.class, null, CallContext.standalone(clock.instant().plusSeconds(60)));
        assertThat(logs.rows.get(0).discarded()).isTrue();
        assertThat(logs.rows.get(1).caseId()).isNull();
        assertThat(logs.rows.get(1).stepSeq()).isNull();
        assertThat(logs.rows.get(1).discarded()).isFalse();
    }

    @Test
    void temperatureIsNullWhenProviderDoesNotSendIt() {
        LlmProvider noTemp = new LlmProvider() {
            public String name() { return "no-temp"; }
            public boolean sendsTemperature() { return false; }
            public ProviderResponse generate(ProviderRequest r) {
                assertThat(r.temperature()).isNull();
                return FakeProvider.ok("{\"echo\":\"a\"}");
            }
        };
        LlmGateway g = new LlmGateway(noTemp, prompts, logs, json, new AppClock(clock), props, d -> {});
        LlmResult<Echo> r = g.call("T_echo", Map.of("word", "a"), Echo.class, null, ctx());
        assertThat(r.temperature()).isNull();
        assertThat(logs.rows.get(0).temperature()).isNull();
    }

    static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant start) { this.now = start; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
