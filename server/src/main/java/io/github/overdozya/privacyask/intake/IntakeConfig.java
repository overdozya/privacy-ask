package io.github.overdozya.privacyask.intake;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.overdozya.privacyask.common.AppClock;
import io.github.overdozya.privacyask.common.CodeVersion;
import io.github.overdozya.privacyask.llm.LlmGateway;
import io.github.overdozya.privacyask.record.CaseLock;
import io.github.overdozya.privacyask.record.CaseStore;

@Configuration(proxyBeanMethods = false)
public class IntakeConfig {

    @Bean
    IntakeStep intakeStep(LlmGateway llm, CaseStore store, CaseLock lock, JdbcClient jdbc, AppClock clock,
                          CodeVersion code) {
        return new IntakeStep(llm, store, lock, jdbc, clock, code);
    }
}
