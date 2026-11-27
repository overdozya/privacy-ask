package io.github.overdozya.privacyask.answer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.overdozya.privacyask.common.AppClock;
import io.github.overdozya.privacyask.common.CodeVersion;
import io.github.overdozya.privacyask.knowledge.Knowledge;
import io.github.overdozya.privacyask.llm.LlmGateway;
import io.github.overdozya.privacyask.record.CaseLock;
import io.github.overdozya.privacyask.record.CaseStore;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class AnswerConfig {

    @Bean
    SpecificAnswerStep specificAnswerStep(LlmGateway llm, CaseStore store, CaseLock lock, JdbcClient jdbc,
                                          Knowledge knowledge, AppClock clock, CodeVersion code, JsonMapper json) {
        return new SpecificAnswerStep(llm, store, lock, jdbc, knowledge, clock, code, json);
    }

    @Bean
    GeneralAnswerStep generalAnswerStep(LlmGateway llm, CaseStore store, CaseLock lock, JdbcClient jdbc,
                                        Knowledge knowledge, AppClock clock, CodeVersion code, JsonMapper json) {
        return new GeneralAnswerStep(llm, store, lock, jdbc, knowledge, clock, code, json);
    }
}
