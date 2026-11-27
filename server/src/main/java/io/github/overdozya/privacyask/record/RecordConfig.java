package io.github.overdozya.privacyask.record;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.overdozya.privacyask.knowledge.Knowledge;
import io.github.overdozya.privacyask.llm.PromptCatalog;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class RecordConfig {

    @Bean
    PromptCheck promptCheck(PromptCatalog catalog) {
        return catalog::has;
    }

    @Bean
    CaseStore caseStore(JdbcClient jdbc, PlatformTransactionManager tm, Knowledge knowledge, JsonMapper json,
                        PromptCheck prompts) {
        return new CaseStore(jdbc, new TransactionTemplate(tm), knowledge, json, prompts);
    }

    @Bean
    CaseReader caseReader(JdbcClient jdbc, JsonMapper json) {
        return new CaseReader(jdbc, json);
    }

    @Bean
    CaseLock caseLock(JdbcClient jdbc, PlatformTransactionManager tm) {
        return new CaseLock(jdbc, new TransactionTemplate(tm));
    }
}
