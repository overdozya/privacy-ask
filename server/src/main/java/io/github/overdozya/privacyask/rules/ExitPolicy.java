package io.github.overdozya.privacyask.rules;

import java.util.Collection;
import java.util.Map;

/**
 * 출구 정책(06 3.3 예비 출구): 이관 사유가 하나라도 있으면 TRANSFER, 없으면 rule_sets.exit_policy[사안 등급].
 * 쟁점이 하나도 없으면(등급 없음) 정책을 볼 등급이 없으므로 TRANSFER로 둔다(즉답 근거 없음, 결과 메모 '다르게 읽은 곳').
 */
public final class ExitPolicy {
    public static final String TRANSFER = "TRANSFER", ANSWER = "ANSWER";

    private final Map<String, String> byGrade;

    public ExitPolicy(Map<String, String> byGrade) {
        this.byGrade = Map.copyOf(byGrade);
    }

    public String exit(String caseGrade, Collection<?> reasons) {
        if (!reasons.isEmpty() || caseGrade == null) {
            return TRANSFER;
        }
        String e = byGrade.get(caseGrade);
        if (e == null) {
            throw new IllegalStateException("출구 정책에 등급이 없음: " + caseGrade);
        }
        return e;
    }

    public Map<String, String> byGrade() {
        return byGrade;
    }
}
