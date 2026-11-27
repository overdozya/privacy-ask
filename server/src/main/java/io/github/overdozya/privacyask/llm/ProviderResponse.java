package io.github.overdozya.privacyask.llm;

/** 공급자가 돌려준 것(06 4.1). 토큰은 공급자가 안 주면 null. */
public record ProviderResponse(
        String text,
        String finishReason,
        Integer promptTokens,
        Integer outputTokens,
        Integer thoughtTokens,
        int httpStatus,
        long latencyMs,
        String modelVersion) {}
