package io.github.overdozya.privacyask.issues;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import io.github.overdozya.privacyask.knowledge.KnowledgeSnapshot;
import io.github.overdozya.privacyask.record.CaseState;
import io.github.overdozya.privacyask.rules.ExitPolicy;

/**
 * A3 쟁점 + 예비 출구를 한 번에(06 3.3·3.4 "질문 없음 → A3 쟁점 → 누락 감시 → 예비 출구"). S7 TurnService가 부르는 진입점.
 * 흐름마다 SlotConverter → IssueDeriver, 쟁점은 흐름별로 두고 사안 등급은 가장 높은 것(결정 4), 출구는 PreliminaryExit.
 * 메모리 계산뿐이다(DB·LLM 없음). 같은 입력이면 같은 결과.
 */
public final class CaseJudge {

    /** 사안 판정: 흐름별 슬롯·쟁점, 사안 등급, 예비 출구. */
    public record Judgment(List<IssueDeriver.FlowIssues> flows, String caseGrade, PreliminaryExit.Exit exit) {
        public IssueDeriver.FlowIssues flow(int flowNo) {
            return flows.stream().filter(f -> f.flowNo() == flowNo).findFirst().orElseThrow();
        }
    }

    private final SlotConverter converter;
    private final IssueDeriver deriver;
    private final PreliminaryExit exit;
    private final KnowledgeSnapshot snapshot;   // CaseState 판정에만(원장 조회). 테스트로 지은 엔진이면 null

    public CaseJudge(SlotConverter converter, IssueDeriver deriver, PreliminaryExit exit, KnowledgeSnapshot snapshot) {
        this.converter = converter;
        this.deriver = deriver;
        this.exit = exit;
        this.snapshot = snapshot;
    }

    public static CaseJudge of(KnowledgeSnapshot s) {
        return new CaseJudge(SlotConverter.of(s.catalog(), s.slotRules()), IssueDeriver.of(s.packages(), s.ruleSet()),
                new PreliminaryExit(new ExitPolicy(s.ruleSet().exitPolicy())), s);
    }

    public SlotConverter converter() {
        return converter;
    }

    public IssueDeriver deriver() {
        return deriver;
    }

    public PreliminaryExit exitPolicy() {
        return exit;
    }

    public IssueDeriver.FlowIssues judgeFlow(FlowFacts facts) {
        return judgeFlow(facts, Map.of());
    }

    /** forced: A2 보조 계산에서 가정한 슬롯(SlotConverter.convert 참조). */
    public IssueDeriver.FlowIssues judgeFlow(FlowFacts facts, Map<String, SlotResult> forced) {
        return deriver.derive(facts, converter.convert(facts, forced));
    }

    /** external: OUT_OF_SCOPE·OUTSIDE_KB_LAW·WATCH_FLAG처럼 판정 밖에서 온 이관 사유. */
    public Judgment judge(Collection<FlowFacts> flows, Collection<PreliminaryExit.Reason> external) {
        List<IssueDeriver.FlowIssues> fs = new ArrayList<>();
        for (FlowFacts f : flows) {
            fs.add(judgeFlow(f));
        }
        return combine(fs, external);
    }

    public Judgment combine(List<IssueDeriver.FlowIssues> flows, Collection<PreliminaryExit.Reason> external) {
        PreliminaryExit.Exit e = exit.decide(flows, external);
        return new Judgment(List.copyOf(flows), e.caseGrade(), e);
    }

    /** 사안 상태에서 바로(지금 사실 판). */
    public Judgment judge(CaseState state, Collection<PreliminaryExit.Reason> external) {
        return judge(flows(state), external);
    }

    public List<FlowFacts> flows(CaseState state) {
        if (snapshot == null) {
            throw new IllegalStateException("스냅샷 없이 지은 판정기는 CaseState를 읽지 못함(원장 조회)");
        }
        return CaseFacts.flows(state, snapshot.ledger());
    }
}
