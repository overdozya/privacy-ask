package io.github.overdozya.privacyask.verify;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.overdozya.privacyask.common.AppClock;
import io.github.overdozya.privacyask.common.CodeVersion;
import io.github.overdozya.privacyask.llm.LlmGateway;
import io.github.overdozya.privacyask.record.CaseLock;
import io.github.overdozya.privacyask.record.CaseStore;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class VerifyConfig {

    @Bean
    ReviewSummaryStep reviewSummaryStep(LlmGateway llm, CaseStore store, CaseLock lock, JdbcClient jdbc, AppClock clock,
                                        CodeVersion code) {
        return new ReviewSummaryStep(llm, store, lock, jdbc, clock, code);
    }

    @Bean
    JudgmentRecorder judgmentRecorder(CaseStore store, CaseLock lock, CodeVersion code) {
        return new JudgmentRecorder(store, lock, code);
    }

    @Bean
    JudgmentReader judgmentReader(JdbcClient jdbc, JsonMapper json) {
        return new JudgmentReader(jdbc, json);
    }
}
