package io.github.overdozya.privacyask.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.overdozya.privacyask.answer.Answer;
import io.github.overdozya.privacyask.issues.CaseJudge;
import io.github.overdozya.privacyask.issues.IssueDeriver;
import io.github.overdozya.privacyask.issues.PreliminaryExit;
import io.github.overdozya.privacyask.issues.SlotResult;
import io.github.overdozya.privacyask.rules.ExitPolicy;

/**
 * A7 최종 출구(03 4.2 A7 4, 06 3.3 "예비 출구 → A7이 대신"). 코드가 정하고 감사 대상이다.
 *
 * <p>이관 사유를 모두 모아 하나라도 있으면 TRANSFER, 없으면 exit_policy[사안 등급]. 사유 목록은 이 패키지에 둔다(최종 사유 목록):
 * 예비 출구 사유(pa/issues, 코드 그대로) + A5가 더한 사유(코드 그대로) + A7이 더하는 사유 다섯.
 * <ul>
 * <li>OPEN_UNKNOWN: 판정 때 OPEN으로 남은 슬롯(질문 상한 등으로 안 물은 사실이 막음). 판정 행에는 UNKNOWN으로 적고 출구에서도 모름으로
 * 본다(메인 결정, 보수적). 현업이 '잘 모르겠음'을 고른 REQUESTER_UNKNOWN과 가른다</li>
 * <li>NOT_CONCLUSIVE: 결론 미확정 쟁점(모델 표시·해석 쟁점·gap, 코드가 정한 A5 conclusive)</li>
 * <li>NO_ISSUE: 쟁점이 하나도 없음(정책을 볼 등급이 없음, S5 다른 곳 4)</li>
 * <li>GRADE_POLICY: 출구 정책이 그 등급을 이관으로 둠(v1.0: H·M → TRANSFER). 다른 사유가 없어도 이관의 까닭으로 남긴다</li>
 * <li>CITATION_UNVERIFIED: 저장된 답을 다시 본 인용 검증이 실패(A5 업무 검사를 지났는데 그 뒤 실패하면 지식이 바뀐 것, 이관으로 안전하게)</li>
 * </ul>
 */
public final class ExitAssembler {
    public static final String OPEN_UNKNOWN = "OPEN_UNKNOWN", NOT_CONCLUSIVE = "NOT_CONCLUSIVE", NO_ISSUE = "NO_ISSUE",
            GRADE_POLICY = "GRADE_POLICY", CITATION_UNVERIFIED = "CITATION_UNVERIFIED";
    public static final String FROM_A3 = "A3", FROM_A5 = "A5", FROM_A7 = "A7";
    /** 이 사유가 있으면 사안 결론이 하나로 안 정해진다(모르는 사실·사실 충돌·확인 사항·해석·꾸러미 밖·미확정 쟁점). */
    static final java.util.Set<String> UNSETTLED = java.util.Set.of(PreliminaryExit.REQUESTER_UNKNOWN, OPEN_UNKNOWN,
            PreliminaryExit.FACT_CONFLICT, PreliminaryExit.CHECK_ITEM, PreliminaryExit.INTERP_ISSUE,
            Answer.GAP_INTERPRETATION, Answer.GAP_OUTSIDE_PACKAGE, NOT_CONCLUSIVE);

    /** 최종 사유 하나. from = 어느 단계가 낸 사유인가(A3 예비 출구·A5 답·A7). */
    public record Reason(String code, Integer flow, String detail, String from) {
        public String line() {
            return code + (flow == null ? "" : "(흐름 " + flow + ")") + (detail == null || detail.isBlank() ? "" : ": " + detail);
        }
    }

    /** OPEN으로 남은 슬롯 하나(판정 행에는 UNKNOWN, unknownFacts = 원래 미확인 + 막은 사실). */
    public record OpenSlot(int flow, String slot, List<String> blocking) {}

    /** exitType ANSWER·TRANSFER. conclusive = 사안 결론이 하나로 정해짐(답 conclusive이고 UNSETTLED 사유 없음). */
    public record FinalExit(String exitType, String caseGrade, List<Reason> reasons, boolean conclusive,
                            List<OpenSlot> openSlots) {
        public boolean transfer() {
            return ExitPolicy.TRANSFER.equals(exitType);
        }

        public List<String> lines() {
            return reasons.stream().map(Reason::line).toList();
        }
    }

    private final ExitPolicy policy;

    public ExitAssembler(ExitPolicy policy) {
        this.policy = policy;
    }

    /** 구체 사안. judgment = 외부 사유(법령·감시)를 넣어 다시 낸 판정, answer = A5 답, report = A7 인용 검증. */
    public FinalExit specific(CaseJudge.Judgment judgment, Answer answer, CitationVerifier.Report report) {
        List<Reason> rs = new ArrayList<>();
        for (PreliminaryExit.Reason r : judgment.exit().reasons()) {
            rs.add(new Reason(r.code(), r.flowNo(), r.detail(), FROM_A3));
        }
        List<OpenSlot> open = openSlots(judgment);
        for (OpenSlot o : open) {
            rs.add(new Reason(OPEN_UNKNOWN, o.flow(), o.slot() + ": " + String.join(",", o.blocking()), FROM_A7));
        }
        if (answer != null) {
            answer.addedReasons().forEach(a -> rs.add(new Reason(a.code(), a.flow(), a.detail(), FROM_A5)));
            if (answer.issues() != null) {
                for (Answer.IssueAnswer i : answer.issues()) {
                    if (!i.conclusive()) {
                        rs.add(new Reason(NOT_CONCLUSIVE, i.flow(), i.issue() + ": " + String.join("·", i.notConclusive()),
                                FROM_A7));
                    }
                }
            }
        }
        if (report != null && !report.ok()) {
            rs.add(new Reason(CITATION_UNVERIFIED, null, report.reason(), FROM_A7));
        }
        String grade = judgment.caseGrade();
        if (grade == null) {
            rs.add(new Reason(NO_ISSUE, null, "쟁점 없음: 출구 정책을 볼 등급이 없음", FROM_A7));
        } else if (ExitPolicy.TRANSFER.equals(policy.byGrade().get(grade))) {
            rs.add(new Reason(GRADE_POLICY, null, "exit_policy[" + grade + "] = TRANSFER", FROM_A7));
        }
        String exit = rs.isEmpty() ? policy.exit(grade, List.of()) : ExitPolicy.TRANSFER;
        boolean conclusive = answer != null && answer.conclusive()
                && rs.stream().noneMatch(r -> UNSETTLED.contains(r.code()));
        return new FinalExit(exit, grade, List.copyOf(rs), conclusive, open);
    }

    /** 일반 문의(G2): 근거 없음(NO_BASIS)이나 인용 검증 실패면 TRANSFER(검토 안내), 아니면 ANSWER. 등급 정책은 보지 않는다(06 4.3). */
    public FinalExit general(Answer answer, CitationVerifier.Report report) {
        List<Reason> rs = new ArrayList<>();
        answer.addedReasons().forEach(a -> rs.add(new Reason(a.code(), a.flow(), a.detail(), FROM_A5)));
        if (report != null && !report.ok()) {
            rs.add(new Reason(CITATION_UNVERIFIED, null, report.reason(), FROM_A7));
        }
        String exit = rs.isEmpty() ? ExitPolicy.ANSWER : ExitPolicy.TRANSFER;
        return new FinalExit(exit, null, List.copyOf(rs), rs.isEmpty() && answer.conclusive(), List.of());
    }

    /** 판정 때 OPEN인 슬롯(흐름·슬롯 순): 막은 사실 = openFacts. */
    public static List<OpenSlot> openSlots(CaseJudge.Judgment j) {
        List<OpenSlot> out = new ArrayList<>();
        for (IssueDeriver.FlowIssues f : j.flows()) {
            for (Map.Entry<String, SlotResult> e : f.slots().entrySet()) {
                if (SlotResult.OPEN.equals(e.getValue().status())) {
                    out.add(new OpenSlot(f.flowNo(), e.getKey(), e.getValue().openFacts()));
                }
            }
        }
        return out;
    }
}
