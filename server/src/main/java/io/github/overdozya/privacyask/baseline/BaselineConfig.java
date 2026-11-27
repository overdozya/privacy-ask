package io.github.overdozya.privacyask.baseline;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.overdozya.privacyask.common.AppClock;
import io.github.overdozya.privacyask.common.CodeVersion;
import io.github.overdozya.privacyask.knowledge.Knowledge;
import io.github.overdozya.privacyask.llm.LlmGateway;
import io.github.overdozya.privacyask.record.CaseLock;
import io.github.overdozya.privacyask.record.CaseStore;

@Configuration(proxyBeanMethods = false)
public class BaselineConfig {

    @Bean
    BaselineStep baselineStep(LlmGateway llm, CaseStore store, CaseLock lock, JdbcClient jdbc, Knowledge knowledge,
                              AppClock clock, CodeVersion code) {
        return new BaselineStep(llm, store, lock, jdbc, knowledge, clock, code);
    }
}
