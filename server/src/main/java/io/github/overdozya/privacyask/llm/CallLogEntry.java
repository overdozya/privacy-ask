package io.github.overdozya.privacyask.llm;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** llm_call_logs 한 행 = 시도 하나(06 4.4). */
public record CallLogEntry(
        String caseId,
        Integer stepSeq,
        String promptId,
        String promptVersion,
        String provider,
        String model,
        String modelVersion,
        BigDecimal temperature,
        int attemptNo,
        Outcome outcome,
        Integer httpStatus,
        String finishReason,
        Long latencyMs,
        Integer promptTokens,
        Integer outputTokens,
        Integer thoughtTokens,
        String inputSha256,
        String responseText,
        String error,
        boolean discarded,
        LocalDateTime at,
        Integer cachedTokens) {

    /** cachedTokens 없이(S8b 전 꼴). */
    public CallLogEntry(String caseId, Integer stepSeq, String promptId, String promptVersion, String provider,
                        String model, String modelVersion, BigDecimal temperature, int attemptNo, Outcome outcome,
                        Integer httpStatus, String finishReason, Long latencyMs, Integer promptTokens,
                        Integer outputTokens, Integer thoughtTokens, String inputSha256, String responseText,
                        String error, boolean discarded, LocalDateTime at) {
        this(caseId, stepSeq, promptId, promptVersion, provider, model, modelVersion, temperature, attemptNo, outcome,
                httpStatus, finishReason, latencyMs, promptTokens, outputTokens, thoughtTokens, inputSha256,
                responseText, error, discarded, at, null);
    }

    public enum Outcome { OK, SCHEMA_FAIL, CHECK_FAIL, FINISH_FAIL, HTTP_ERROR, TIMEOUT }
}
