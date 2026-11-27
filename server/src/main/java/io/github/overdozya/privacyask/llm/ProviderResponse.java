package io.github.overdozya.privacyask.llm;

/** 공급자가 돌려준 것(06 4.1). 토큰은 공급자가 안 주면 null. cachedTokens = 입력 중 공급자 캐시에서 온 토큰(S8b, Gemini cachedContentTokenCount). */
public record ProviderResponse(
        String text,
        String finishReason,
        Integer promptTokens,
        Integer outputTokens,
        Integer thoughtTokens,
        int httpStatus,
        long latencyMs,
        String modelVersion,
        Integer cachedTokens) {

    public ProviderResponse(String text, String finishReason, Integer promptTokens, Integer outputTokens,
                            Integer thoughtTokens, int httpStatus, long latencyMs, String modelVersion) {
        this(text, finishReason, promptTokens, outputTokens, thoughtTokens, httpStatus, latencyMs, modelVersion, null);
    }
}
