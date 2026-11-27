package io.github.overdozya.privacyask.llm;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.overdozya.privacyask.common.AppClock;
import io.github.overdozya.privacyask.llm.CallLogEntry.Outcome;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * LLM 경계(06 4.1~4.4): 프롬프트 글 만들기 · 스키마 검증 · 업무 검사 · 재시도 · 기록.
 * <ul>
 * <li>검증 실패(JSON 아님, 스키마 위반, 업무 검사 거절, 종료 사유 STOP 아님): 1회 다시(실패 이유 한 줄을 user 글 끝에)</li>
 * <li>전송 실패(429·5xx·시간 초과·연결 오류): 1회 다시(1~2초, 429는 Retry-After를 마감 안에서)</li>
 * <li>그 밖(400·401·403·설정 오류): 다시 하지 않음</li>
 * <li>시도는 합쳐 최대 3번. 시도마다 마감은 min(호출 상한, 턴 남은 시간). 마감을 넘기면 다시 하지 않는다</li>
 * </ul>
 * 다 실패하면 LLM_FAILED(단계가 steps에 남기고 503을 낸다, 06 3.4).
 */
public class LlmGateway {
    public static final int MAX_ATTEMPTS = 3;
    private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);

    /** 업무 검사: 거절이면 이유 한 줄, 통과면 null. */
    @FunctionalInterface
    public interface Check<T> extends Function<T, String> {}

    /** 기다리기(테스트에서 바꿈). */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    private final LlmProvider provider;
    private final PromptCatalog prompts;
    private final OutputValidator validator;
    private final LlmCallLogWriter logs;
    private final JsonMapper json;
    private final AppClock clock;
    private final LlmProperties props;
    private final Sleeper sleeper;

    public LlmGateway(LlmProvider provider, PromptCatalog prompts, LlmCallLogWriter logs, JsonMapper json,
                      AppClock clock, LlmProperties props, Sleeper sleeper) {
        this.provider = provider;
        this.prompts = prompts;
        this.validator = new OutputValidator(json);
        this.logs = logs;
        this.json = json;
        this.clock = clock;
        this.props = props;
        this.sleeper = sleeper;
    }

    public LlmProvider provider() {
        return provider;
    }

    public PromptCatalog prompts() {
        return prompts;
    }

    public <T> LlmResult<T> call(String promptId, Object input, Class<T> type, Check<T> check, CallContext ctx) {
        Prompt prompt = prompts.get(promptId);
        String userBase = input instanceof String s ? s : json.writeValueAsString(input);
        String inputSha = PromptCatalog.sha256(userBase);
        BigDecimal temperature = provider.sendsTemperature() ? props.temperature() : null;

        List<Long> callIds = new ArrayList<>();
        int attempts = 0;
        boolean validationRetried = false;
        boolean transportRetried = false;
        String feedback = null;
        String lastError = null;

        while (attempts < MAX_ATTEMPTS) {
            Duration remaining = Duration.between(clock.now(), ctx.deadline());
            if (remaining.isNegative() || remaining.isZero()) {
                lastError = "턴 마감을 넘김";
                break;
            }
            Duration timeout = remaining.compareTo(props.callTimeout()) < 0 ? remaining : props.callTimeout();
            attempts++;
            String user = feedback == null ? userBase
                    : userBase + "\n\n[다시 요청] 앞 출력이 거절됐다: " + feedback + " — 스키마에 맞는 JSON 하나만 다시 낸다.";
            ProviderRequest req = new ProviderRequest(promptId, prompt.system(), user, prompt.schema(),
                    temperature, prompt.maxOutputTokens(), prompt.thinking(), timeout);

            ProviderResponse res;
            try {
                res = provider.generate(req);
            } catch (ProviderException e) {
                Outcome outcome = e.kind() == ProviderException.Kind.TIMEOUT ? Outcome.TIMEOUT : Outcome.HTTP_ERROR;
                lastError = e.getMessage();
                callIds.add(logs.write(entry(ctx, prompt, temperature, attempts, outcome, e.httpStatus(), null,
                        e.latencyMs(), null, null, null, null, inputSha, e.body(), e.getMessage())));
                if (!e.retryable() || transportRetried || attempts >= MAX_ATTEMPTS) {
                    break;
                }
                Duration wait = e.retryAfter() != null ? e.retryAfter() : jittered(props.retryBackoff());
                if (clock.now().plus(wait).isAfter(ctx.deadline().minusMillis(500))) {
                    lastError = e.getMessage() + " (다시 할 시간 없음)";
                    break;
                }
                transportRetried = true;
                if (!sleep(wait)) {
                    break;
                }
                continue;
            }

            Outcome outcome;
            String reason;
            JsonNode node = null;
            T value = null;
            if (!"STOP".equals(res.finishReason())) {
                outcome = Outcome.FINISH_FAIL;
                reason = "종료 사유 " + res.finishReason();
            } else {
                OutputValidator.Checked c = validator.check(prompt, res.text());
                node = c.node();
                if (!c.ok()) {
                    outcome = Outcome.SCHEMA_FAIL;
                    reason = c.error();
                } else {
                    try {
                        value = convert(node, type);
                        String rejected = check == null ? null : check.apply(value);
                        outcome = rejected == null ? Outcome.OK : Outcome.CHECK_FAIL;
                        reason = rejected == null ? null : "업무 검사 거절: " + rejected;
                    } catch (JacksonException | IllegalArgumentException e) {
                        outcome = Outcome.SCHEMA_FAIL;
                        reason = "출력 타입으로 바꾸지 못함: " + e.getMessage();
                    }
                }
            }
            callIds.add(logs.write(entry(ctx, prompt, temperature, attempts, outcome, res.httpStatus(),
                    res.finishReason(), res.latencyMs(), res.promptTokens(), res.outputTokens(), res.thoughtTokens(),
                    res.modelVersion(), inputSha, res.text(), reason)));
            if (outcome == Outcome.OK) {
                LlmResult.Status st = attempts == 1 ? LlmResult.Status.OK : LlmResult.Status.RETRY_OK;
                return new LlmResult<>(value, node, st, attempts, provider.name(), props.model(), temperature,
                        promptId, prompt.versionTag(), List.copyOf(callIds), null);
            }
            lastError = reason;
            if (validationRetried) {
                break;
            }
            validationRetried = true;
            feedback = oneLine(reason);
        }
        log.warn("LLM_FAILED prompt={} attempts={} error={}", promptId, attempts, lastError);
        return new LlmResult<>(null, null, LlmResult.Status.LLM_FAILED, Math.max(attempts, 1), provider.name(),
                props.model(), temperature, promptId, prompt.versionTag(), List.copyOf(callIds), lastError);
    }

    @SuppressWarnings("unchecked")
    private <T> T convert(JsonNode node, Class<T> type) {
        if (type == JsonNode.class || type.isInstance(node)) {
            return (T) node;
        }
        return json.treeToValue(node, type);
    }

    private CallLogEntry entry(CallContext ctx, Prompt prompt, BigDecimal temperature, int attempt, Outcome outcome,
                               Integer httpStatus, String finish, long latency, Integer pt, Integer ot, Integer tt,
                               String modelVersion, String inputSha, String responseText, String error) {
        return new CallLogEntry(ctx.caseId(), ctx.stepSeq(), prompt.id(), prompt.versionTag(), provider.name(),
                props.model(), modelVersion, temperature, attempt, outcome, httpStatus, finish, latency, pt, ot, tt,
                inputSha, responseText, error, ctx.discarded(), clock.nowUtc());
    }

    private static Duration jittered(Duration base) {
        return base.plusMillis(ThreadLocalRandom.current().nextLong(0, 1000));
    }

    private boolean sleep(Duration d) {
        try {
            sleeper.sleep(d);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String oneLine(String s) {
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }
}
