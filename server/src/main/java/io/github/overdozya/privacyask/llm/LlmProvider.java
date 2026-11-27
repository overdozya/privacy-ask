package io.github.overdozya.privacyask.llm;

/**
 * 공급자 경계(06 4.1). 재시도·검증·기록은 LlmGateway에 있으므로 구현은 요청 하나를 보내고 받기만 한다.
 * 다른 공급자(예: 현업 Bedrock)는 이 구현 하나를 더한다.
 */
public interface LlmProvider {
    /** llm_call_logs.provider에 남는 이름. */
    String name();

    /** 요청에 온도를 실어 보내는가. false면 호출 기록의 temperature는 NULL(보내지 않은 값을 쓴 것처럼 남기지 않는다). */
    default boolean sendsTemperature() {
        return true;
    }

    ProviderResponse generate(ProviderRequest request) throws ProviderException;
}
