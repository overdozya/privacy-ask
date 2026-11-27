package io.github.overdozya.privacyask.llm;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.overdozya.privacyask.common.AppClock;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class LlmConfig {
    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    LlmProvider llmProvider(LlmProperties props, JsonMapper json) {
        LlmProvider p = switch (props.provider()) {
            case "gemini" -> new GeminiProvider(props, GeminiProvider.defaultClient(), json);
            case "fake" -> new FakeProvider();
            default -> throw new IllegalStateException("LLM_PROVIDER는 gemini / fake: " + props.provider());
        };
        log.info("LLM 공급자 {} 모델 {} 온도 {} 키 {}", p.name(), props.model(), props.temperature(),
                props.hasApiKey() ? "있음" : "없음");
        return p;
    }

    @Bean
    PromptCatalog promptCatalog(JsonMapper json) {
        PromptCatalog c = PromptCatalog.load("classpath:prompts", json);
        c.all().forEach(p -> log.info("프롬프트 {} {}", p.id(), p.versionTag()));
        return c;
    }

    @Bean
    LlmCallLogWriter llmCallLogWriter(JdbcClient jdbc) {
        return new JdbcLlmCallLogWriter(jdbc);
    }

    @Bean
    LlmGateway llmGateway(LlmProvider provider, PromptCatalog prompts, LlmCallLogWriter logs, JsonMapper json,
                          AppClock clock, LlmProperties props) {
        return new LlmGateway(provider, prompts, logs, json, clock, props, (Duration d) -> Thread.sleep(d));
    }
}
