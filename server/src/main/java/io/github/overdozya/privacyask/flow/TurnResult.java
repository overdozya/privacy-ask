package io.github.overdozya.privacyask.flow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.overdozya.privacyask.answer.Answer;
import io.github.overdozya.privacyask.issues.CaseJudge;
import io.github.overdozya.privacyask.issues.IssueDeriver;
import io.github.overdozya.privacyask.issues.PreliminaryExit;
import io.github.overdozya.privacyask.issues.SlotResult;

/**
 * 한 턴 응답(06 5절 TurnResult). outcome: QUESTION(질문 하나) · RESULT(예비 결과 + 답 A5) · GENERAL(일반 문의, 답 A5 G2) ·
 * TRANSFERRED(범위 밖 이관). question과 result 중 하나만 있다. timing = 이번 요청의 숫자(turn_logs와 같은 값).
 */
public record TurnResult(String caseId, String state, String outcome, Intake intake, Question question, Result result,
                         List<Integer> stepsAppended, Timing timing) {
    public static final String QUESTION = "QUESTION", RESULT = "RESULT", GENERAL = "GENERAL", TRANSFERRED = "TRANSFERRED";

    public record Intake(String type, String outOfScopeKind) {}

    /** turnNo = 이 질문 턴(답할 때 question_turn으로 보냄). flow가 null이면 공통 사실. */
    public record Question(int turnNo, Integer flow, String factId, int askNo, String text, List<String> choices) {}

    /**
     * 예비 결과(첫 묶음). preliminary는 늘 true: 뒤 묶음 A7이 인용 검증을 더해 대신한다(S9).
     * unasked: 질문 상한으로 묻지 않은 후보("흐름:사실", S7b). 답에 따라 쟁점·조건은 바뀔 수 있으나 사안 등급·출구는 안 바뀐다.
     * answer: A5 답(S8, 쟁점별 의견·조치·인용 키와 코드가 붙인 원문 요약·conclusive·gap). 답이 더한 이관 사유는 exitReasons에도 들어가고
     * 그러면 exitType은 TRANSFER. 일반 문의(GENERAL)는 caseGrade·flows가 비고 answer만 있다.
     * S9 최종 결과(A7·A8 뒤, 판정 테이블에서 읽음): preliminary false, exitType·exitReasons = A7 최종 출구(ExitAssembler 사유 코드),
     * judgmentId = case_judgments 행, conclusive = 사안 결론 확정, summary = 이관이면 검토자 요지(A7_summary), verify = 인용 검증 요약.
     * S9b: policy = 출구 정책 표시 {grade, exit}(사유가 아님), unaskedNoEffect = 묻지 않은 사실(결과에 영향 없음, "흐름:사실 (슬롯)").
     */
    public record Result(boolean preliminary, String caseGrade, String exitType, List<Reason> exitReasons,
                         boolean watchFlag, List<FlowResult> flows, List<String> unasked, Answer answer,
                         Long judgmentId, Boolean conclusive, List<String> summary, Object verify, Object policy,
                         List<String> unaskedNoEffect) {

        public Result(boolean preliminary, String caseGrade, String exitType, List<Reason> exitReasons,
                      boolean watchFlag, List<FlowResult> flows, List<String> unasked, Answer answer) {
            this(preliminary, caseGrade, exitType, exitReasons, watchFlag, flows, unasked, answer, null, null, null, null, null,
                    null);
        }

        /** 예비 결과에 답을 붙인다: 답의 이관 사유를 더하고, 사유가 생기면 TRANSFER. */
        public Result withAnswer(Answer a) {
            if (a == null) {
                return this;
            }
            List<Reason> rs = new ArrayList<>(exitReasons);
            a.addedReasons().forEach(x -> rs.add(new Reason(x.code(), x.flow(), x.detail())));
            String exit = rs.isEmpty() ? exitType : "TRANSFER";
            return new Result(preliminary, caseGrade, exit, List.copyOf(rs), watchFlag, flows, unasked, a);
        }
    }

    public record Reason(String code, Integer flow, String detail) {}

    public record FlowResult(int flow, String grade, Map<String, Slot> slots, List<Issue> issues, List<String> firedRules) {}

    public record Slot(String status, List<String> values, List<String> basisFacts, List<String> unknownFacts,
                       List<String> openFacts, List<String> flags) {}

    public record Issue(String name, String packageId, String grade, List<String> ruleIds, List<String> conditionIds,
                        List<String> interpOn, List<String> opens) {}

    public record Timing(int turnMs, int llmCalls, int llmMs, int llmDepth, int dbStatements) {}

    static Result result(CaseJudge.Judgment j, boolean watchFlag) {
        return result(j, watchFlag, List.of());
    }

    static Result result(CaseJudge.Judgment j, boolean watchFlag, List<String> unasked) {
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
        return new Result(true, j.caseGrade(), j.exit().exitType(), reasons, watchFlag, flows, List.copyOf(unasked), null);
    }

    /** 일반 문의 결과: 등급·흐름 없이 답만. 근거 없음(NO_BASIS)이면 TRANSFER(검토 안내), 아니면 ANSWER. */
    static Result general(Answer a) {
        List<Reason> rs = a.addedReasons().stream().map(x -> new Reason(x.code(), x.flow(), x.detail())).toList();
        return new Result(true, null, rs.isEmpty() ? "ANSWER" : "TRANSFER", rs, false, List.of(), List.of(), a);
    }

    static Reason reason(PreliminaryExit.Reason r) {
        return new Reason(r.code(), r.flowNo(), r.detail());
    }
}
