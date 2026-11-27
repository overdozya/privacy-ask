package io.github.overdozya.privacyask.llm;

import java.math.BigDecimal;
import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * 호출 결과(06 4.1). 단계는 이것으로 steps 행(prompt_id, prompt_version, model_name, temperature, attempts, status)을 채운다.
 * status: OK(첫 시도) / RETRY_OK(다시 해서 됨) / LLM_FAILED. value·raw는 LLM_FAILED면 null.
 */
public record LlmResult<T>(
        T value,
        JsonNode raw,
        Status status,
        int attempts,
        String provider,
        String model,
        BigDecimal temperature,
        String promptId,
        String promptVersion,
        List<Long> callIds,
        String lastError) {

    public enum Status { OK, RETRY_OK, LLM_FAILED }

    public boolean ok() {
        return status != Status.LLM_FAILED;
    }
}
