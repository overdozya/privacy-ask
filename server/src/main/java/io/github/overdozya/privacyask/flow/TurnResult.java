package io.github.overdozya.privacyask.flow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.overdozya.privacyask.issues.CaseJudge;
import io.github.overdozya.privacyask.issues.IssueDeriver;
import io.github.overdozya.privacyask.issues.PreliminaryExit;
import io.github.overdozya.privacyask.issues.SlotResult;

/**
 * 한 턴 응답(06 5절 TurnResult). outcome: QUESTION(질문 하나) · RESULT(예비 결과) · GENERAL(일반 문의, 입구 결과만, A5는 S8) ·
 * TRANSFERRED(범위 밖 이관). question과 result 중 하나만 있다. timing = 이번 요청의 숫자(turn_logs와 같은 값).
 */
public record TurnResult(String caseId, String state, String outcome, Intake intake, Question question, Result result,
                         List<Integer> stepsAppended, Timing timing) {
    public static final String QUESTION = "QUESTION", RESULT = "RESULT", GENERAL = "GENERAL", TRANSFERRED = "TRANSFERRED";

    public record Intake(String type, String outOfScopeKind) {}

    /** turnNo = 이 질문 턴(답할 때 question_turn으로 보냄). flow가 null이면 공통 사실. */
    public record Question(int turnNo, Integer flow, String factId, int askNo, String text, List<String> choices) {}

    /** 예비 결과(첫 묶음). preliminary는 늘 true: 뒤 묶음 A7이 인용 검증을 더해 대신한다(S9). */
    public record Result(boolean preliminary, String caseGrade, String exitType, List<Reason> exitReasons,
                         boolean watchFlag, List<FlowResult> flows) {}

    public record Reason(String code, Integer flow, String detail) {}

    public record FlowResult(int flow, String grade, Map<String, Slot> slots, List<Issue> issues, List<String> firedRules) {}

    public record Slot(String status, List<String> values, List<String> basisFacts, List<String> unknownFacts,
                       List<String> openFacts, List<String> flags) {}

    public record Issue(String name, String packageId, String grade, List<String> ruleIds, List<String> conditionIds,
                        List<String> interpOn, List<String> opens) {}

    public record Timing(int turnMs, int llmCalls, int llmMs, int llmDepth, int dbStatements) {}

    static Result result(CaseJudge.Judgment j, boolean watchFlag) {
        List<Reason> reasons = j.exit().reasons().stream().map(TurnResult::reason).toList();
        List<FlowResult> flows = new ArrayList<>();
        for (IssueDeriver.FlowIssues f : j.flows()) {
            Map<String, Slot> slots = new LinkedHashMap<>();
            for (Map.Entry<String, SlotResult> e : f.slots().entrySet()) {
                SlotResult s = e.getValue();
                slots.put(e.getKey(), new Slot(s.status(), s.values(), s.basisFacts(), s.unknownFacts(), s.openFacts(),
                        s.flags()));
            }
            List<Issue> issues = f.issues().stream().map(i -> new Issue(i.issueName(), i.packageId(), i.grade(),
                    i.ruleIds(), i.conditionIds(), i.interpOn(),
                    i.opens().stream().map(IssueDeriver.SubPackage::packageId).toList())).toList();
            flows.add(new FlowResult(f.flowNo(), f.grade(), slots, issues, f.firedRules()));
        }
        return new Result(true, j.caseGrade(), j.exit().exitType(), reasons, watchFlag, flows);
    }

    static Reason reason(PreliminaryExit.Reason r) {
        return new Reason(r.code(), r.flowNo(), r.detail());
    }
}
