package io.github.overdozya.privacyask.issues;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import io.github.overdozya.privacyask.rules.ExitPolicy;
import io.github.overdozya.privacyask.rules.Grades;

/**
 * 예비 출구(06 3.3): 이관 사유를 모아 있으면 TRANSFER, 없으면 exit_policy[사안 등급]. 첫 묶음의 출구 확인용이고,
 * 뒤 묶음에서 A7이 인용 검증을 더해 대신한다(S9).
 *
 * 사유 중 OUT_OF_SCOPE(A0)·OUTSIDE_KB_LAW(LawScopeJudge)·WATCH_FLAG(A3 누락 감시)는 부르는 쪽이 넣고, 나머지는 판정에서 나온다:
 * INTERP_ISSUE(켜진 해석 쟁점), FACT_CONFLICT·CHECK_ITEM(슬롯 표시), REQUESTER_UNKNOWN(슬롯에 현업 미확인, 04 1.9).
 */
public final class PreliminaryExit {
    public static final String OUT_OF_SCOPE = "OUT_OF_SCOPE", OUTSIDE_KB_LAW = "OUTSIDE_KB_LAW",
            WATCH_FLAG = "WATCH_FLAG", INTERP_ISSUE = "INTERP_ISSUE", FACT_CONFLICT = "FACT_CONFLICT",
            CHECK_ITEM = "CHECK_ITEM", REQUESTER_UNKNOWN = "REQUESTER_UNKNOWN";
    public static final List<String> CODES = List.of(OUT_OF_SCOPE, OUTSIDE_KB_LAW, WATCH_FLAG, INTERP_ISSUE,
            FACT_CONFLICT, CHECK_ITEM, REQUESTER_UNKNOWN);

    /** 이관 사유 하나. flowNo는 흐름에서 나온 사유만, detail은 사람이 읽는 짧은 글(슬롯·사실·쟁점 ID). */
    public record Reason(String code, Integer flowNo, String detail) {
        public Reason {
            if (!CODES.contains(code)) {
                throw new IllegalArgumentException("모르는 이관 사유: " + code);
            }
        }
    }

    /** exitType: TRANSFER · ANSWER. caseGrade: 흐름 등급 중 가장 높은 것(결정 4), 쟁점이 없으면 null. */
    public record Exit(String exitType, String caseGrade, List<Reason> reasons) {}

    private final ExitPolicy policy;

    public PreliminaryExit(ExitPolicy policy) {
        this.policy = policy;
    }

    public ExitPolicy policy() {
        return policy;
    }

    public static String caseGrade(Collection<IssueDeriver.FlowIssues> flows) {
        List<String> gs = new ArrayList<>();
        flows.forEach(f -> f.issues().forEach(i -> gs.add(i.grade())));
        return Grades.max(gs);
    }

    /** 흐름 판정에서 나오는 사유, 흐름·슬롯(sort_order)·쟁점 순. */
    public static List<Reason> reasonsOf(IssueDeriver.FlowIssues f) {
        List<Reason> out = new ArrayList<>();
        for (Map.Entry<String, SlotResult> e : f.slots().entrySet()) {
            SlotResult s = e.getValue();
            for (String flag : s.flags()) {
                out.add(new Reason(flag, f.flowNo(), e.getKey()));
            }
            if (s.hasUnknown()) {
                out.add(new Reason(REQUESTER_UNKNOWN, f.flowNo(), e.getKey() + ": " + String.join(",", s.unknownFacts())));
            }
        }
        for (IssueDeriver.Issue i : f.issues()) {
            for (String id : i.allInterpOn()) {
                out.add(new Reason(INTERP_ISSUE, f.flowNo(), i.packageId() + " " + id));
            }
        }
        return out;
    }

    public Exit decide(Collection<IssueDeriver.FlowIssues> flows, Collection<Reason> external) {
        List<Reason> reasons = new ArrayList<>(external);
        flows.forEach(f -> reasons.addAll(reasonsOf(f)));
        String grade = caseGrade(flows);
        return new Exit(policy.exit(grade, reasons), grade, List.copyOf(reasons));
    }
}
