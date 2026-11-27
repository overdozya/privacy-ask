package io.github.overdozya.privacyask.facts;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.overdozya.privacyask.common.AppClock;
import io.github.overdozya.privacyask.common.CodeVersion;
import io.github.overdozya.privacyask.knowledge.Knowledge;
import io.github.overdozya.privacyask.llm.LlmGateway;
import io.github.overdozya.privacyask.record.CaseLock;
import io.github.overdozya.privacyask.record.CaseStore;

@Configuration(proxyBeanMethods = false)
public class FactsConfig {

    @Bean
    FactExtractStep factExtractStep(LlmGateway llm, CaseStore store, CaseLock lock, JdbcClient jdbc,
                                    PlatformTransactionManager tm, Knowledge knowledge, AppClock clock, CodeVersion code) {
        return new FactExtractStep(llm, store, lock, jdbc, new TransactionTemplate(tm), knowledge, clock, code);
    }

    @Bean
    AnswerApplier answerApplier(CaseStore store, CaseLock lock, Knowledge knowledge, AppClock clock, CodeVersion code) {
        return new AnswerApplier(store, lock, knowledge, clock, code);
    }
}
