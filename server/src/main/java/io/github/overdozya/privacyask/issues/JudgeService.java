package io.github.overdozya.privacyask.issues;

import java.util.Collection;

import org.springframework.stereotype.Component;

import io.github.overdozya.privacyask.knowledge.Knowledge;
import io.github.overdozya.privacyask.knowledge.KnowledgeSnapshot;
import io.github.overdozya.privacyask.record.CaseState;

/**
 * 스프링 진입점(S7 TurnService가 받음): 지식 스냅샷으로 CaseJudge를 한 번 짓고 쓴다. 적재 전 기동(Knowledge EMPTY)에서도
 * 빈은 생기고, 처음 부를 때 snapshot()이 예외를 낸다.
 */
@Component
public class JudgeService {
    private final Knowledge knowledge;
    private volatile CaseJudge judge;
    private volatile KnowledgeSnapshot builtFrom;

    public JudgeService(Knowledge knowledge) {
        this.knowledge = knowledge;
    }

    public CaseJudge judge() {
        KnowledgeSnapshot s = knowledge.snapshot();
        CaseJudge j = judge;
        if (j == null || builtFrom != s) {
            synchronized (this) {
                if (judge == null || builtFrom != s) {
                    judge = CaseJudge.of(s);
                    builtFrom = s;
                }
                j = judge;
            }
        }
        return j;
    }

    /** A3 쟁점 + 예비 출구(지금 사실 판). external: A0 범위 밖·적용 법령·누락 감시 사유. */
    public CaseJudge.Judgment judge(CaseState state, Collection<PreliminaryExit.Reason> external) {
        return judge().judge(state, external);
    }

    public LawScopeJudge lawScope() {
        return LawScopeJudge.of(knowledge.snapshot().documents());
    }
}
