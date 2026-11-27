package io.github.overdozya.privacyask.question;

import java.util.Collection;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.github.overdozya.privacyask.issues.CaseJudge;
import io.github.overdozya.privacyask.issues.JudgeService;
import io.github.overdozya.privacyask.issues.PreliminaryExit;
import io.github.overdozya.privacyask.knowledge.Catalog;
import io.github.overdozya.privacyask.knowledge.Knowledge;
import io.github.overdozya.privacyask.record.CaseState;

/**
 * 스프링 진입점(S7): A2 다음 질문과 질문 글. 사실 순서는 app.question.order(CATALOG 기본 = 06 3.5, RULE = 결과 메모의 제안).
 */
@Component
public class QuestionService {
    private final JudgeService judges;
    private final Knowledge knowledge;
    private final QuestionSelector.Order order;
    private final QuestionSelector.Priority priority;
    private final int maxPerCase;
    private volatile QuestionSelector selector;
    private volatile CaseJudge builtFrom;

    public QuestionService(JudgeService judges, Knowledge knowledge,
                           @Value("${app.question.order:CATALOG}") QuestionSelector.Order order,
                           @Value("${app.question.priority:ORDER}") QuestionSelector.Priority priority,
                           @Value("${app.question.max-per-case:0}") int maxPerCase) {
        this.judges = judges;
        this.knowledge = knowledge;
        this.order = order;
        this.priority = priority;
        this.maxPerCase = maxPerCase;
    }

    public QuestionSelector selector() {
        CaseJudge j = judges.judge();
        QuestionSelector s = selector;
        if (s == null || builtFrom != j) {
            synchronized (this) {
                if (selector == null || builtFrom != j) {
                    selector = new QuestionSelector(j, knowledge.snapshot().catalog(), order, priority, maxPerCase);
                    builtFrom = j;
                }
                s = selector;
            }
        }
        return s;
    }

    /** cloudFlows: A1이 상대를 클라우드·IT 운영사로 낸 흐름(F-T5 후보). external: 예비 출구에 들어갈 판정 밖 사유. */
    public QuestionSelector.Selection next(CaseState state, Set<Integer> cloudFlows,
                                           Collection<PreliminaryExit.Reason> external) {
        return selector().select(state, cloudFlows, external);
    }

    /** 고른 질문의 글·보기. 흐름 사실이면 [받는 쪽]을 앞에. */
    public QuestionText.Text text(CaseState state, QuestionSelector.Ask ask) {
        Catalog.Fact f = knowledge.snapshot().catalog().fact(ask.factId()).orElseThrow();
        String dest = ask.flowNo() == null ? null
                : state.flow(ask.flowNo()).map(CaseState.Flow::destination).orElse(null);
        return QuestionText.of(f, ask.askNo(), dest);
    }
}
