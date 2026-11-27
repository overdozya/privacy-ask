package io.github.overdozya.privacyask.llm;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** app.llm.* (06 1.4·4.2·6.1). toString은 키를 내지 않는다. */
@ConfigurationProperties("app.llm")
public record LlmProperties(
        @DefaultValue("fake") String provider,
        @DefaultValue("gemini-3.6-flash") String model,
        @DefaultValue("0") BigDecimal temperature,
        String apiKey,
        @DefaultValue("20s") Duration callTimeout,
        @DefaultValue("1s") Duration retryBackoff,
        @DefaultValue("false") boolean pingOnStartup,
        @DefaultValue Gemini gemini) {

    public record Gemini(
            @DefaultValue("https://generativelanguage.googleapis.com/v1beta") String baseUrl,
            @DefaultValue("true") boolean sendSchema,
            @DefaultValue("true") boolean sendTemperature) {}

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String toString() {
        return "LlmProperties[provider=" + provider + ", model=" + model + ", temperature=" + temperature
                + ", apiKey=" + (hasApiKey() ? "(set)" : "(empty)") + ", callTimeout=" + callTimeout
                + ", retryBackoff=" + retryBackoff + ", pingOnStartup=" + pingOnStartup + ", gemini=" + gemini + "]";
    }
}
