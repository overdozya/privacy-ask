package io.github.overdozya.privacyask.llm;

import java.math.BigDecimal;
import java.time.Duration;

import tools.jackson.databind.JsonNode;

/**
 * 공급자에게 보내는 것(06 4.1). schema는 선택(null이면 보내지 않음). thinking은 "default"면 보내지 않는다(06 4.2).
 */
public record ProviderRequest(
        String promptId,
        String system,
        String user,
        JsonNode schema,
        BigDecimal temperature,
        int maxOutputTokens,
        String thinking,
        Duration timeout) {}
