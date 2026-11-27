package io.github.overdozya.privacyask.watch;

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
public class WatchConfig {

    @Bean
    OmissionWatchStep omissionWatchStep(LlmGateway llm, CaseStore store, CaseLock lock, JdbcClient jdbc,
                                        Knowledge knowledge, AppClock clock, CodeVersion code) {
        return new OmissionWatchStep(llm, store, lock, jdbc, knowledge, clock, code);
    }
}
