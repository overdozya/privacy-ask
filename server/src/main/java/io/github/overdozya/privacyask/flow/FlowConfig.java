package io.github.overdozya.privacyask.flow;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.sql.DataSource;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.overdozya.privacyask.baseline.BaselineStep;
import io.github.overdozya.privacyask.common.AppClock;
import io.github.overdozya.privacyask.common.CodeVersion;
import io.github.overdozya.privacyask.facts.AnswerApplier;
import io.github.overdozya.privacyask.facts.FactExtractStep;
import io.github.overdozya.privacyask.intake.IntakeStep;
import io.github.overdozya.privacyask.issues.JudgeService;
import io.github.overdozya.privacyask.knowledge.Knowledge;
import io.github.overdozya.privacyask.question.QuestionService;
import io.github.overdozya.privacyask.record.CaseLock;
import io.github.overdozya.privacyask.record.CaseReader;
import io.github.overdozya.privacyask.record.CaseStore;
import io.github.overdozya.privacyask.watch.OmissionWatchStep;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class FlowConfig {
    /**
     * A0 ‖ A1 추측 실행용 가상 스레드(06 6.3). 빈으로 두지 않는다: Executor 빈이 있으면 스프링 부트의 기본 작업 실행기가 물러난다.
     * 가상 스레드는 JVM 종료를 막지 않는다.
     */
    static final ExecutorService FLOW_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /** 데이터 소스를 CountingDataSource로 감싼다(턴 측정이 걸린 스레드의 연결만 센다). */
    @Bean
    static BeanPostProcessor countingDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource ds && !(bean instanceof CountingDataSource)) {
                    return new CountingDataSource(ds);
                }
                return bean;
            }
        };
    }

    @Bean
    TurnLogWriter turnLogWriter(JdbcClient jdbc, JsonMapper json) {
        return new TurnLogWriter(jdbc, json);
    }

    @Bean
    TurnService turnService(CaseStore store, CaseReader reader, CaseLock lock, JdbcClient jdbc, Knowledge knowledge,
                            IntakeStep intake, FactExtractStep facts, AnswerApplier answers, BaselineStep baseline,
                            OmissionWatchStep watch, JudgeService judges, QuestionService questions, AppClock clock,
                            CodeVersion code, FlowProperties props, TurnLogWriter turnLogs, JsonMapper json,
                            io.github.overdozya.privacyask.packages.PackageService packages,
                            io.github.overdozya.privacyask.answer.SpecificAnswerStep specific,
                            io.github.overdozya.privacyask.answer.GeneralAnswerStep general) {
        return new TurnService(store, reader, lock, jdbc, knowledge, intake, facts, answers, baseline, watch, judges,
                questions, clock, code, props, turnLogs, FLOW_EXECUTOR, json,
                new TurnService.AnswerStages(packages, specific, general));
    }

    @Bean
    CaseQueries caseQueries(CaseReader reader, JdbcClient jdbc, TurnService turns, Knowledge knowledge,
                            io.github.overdozya.privacyask.llm.LlmGateway llm,
                            io.github.overdozya.privacyask.llm.LlmProperties llmProps) {
        return new CaseQueries(reader, jdbc, turns, knowledge, llm.prompts(), llmProps);
    }
}
