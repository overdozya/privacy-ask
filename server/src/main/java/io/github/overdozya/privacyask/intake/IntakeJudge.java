package io.github.overdozya.privacyask.intake;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 입구 판정(04 4.3, 결정 14 · 06 3.3 A0). LLM은 신호만 내고 유형은 여기서 정한다.
 * <ol>
 * <li>(다) 사고 → OUT_OF_SCOPE / BREACH. (라) 실제 권리 요청 → OUT_OF_SCOPE / DATA_SUBJECT_REQUEST. 둘 다면 BREACH(사고 대응이 먼저)</li>
 * <li>(가) 자기 사안 → SPECIFIC. (나) 처리 행위만 있고 (가)가 없으면 애매 → SPECIFIC(현업 설계서 4.1 "애매하면 구체")</li>
 * <li>그 밖(신호 없음, (마)만) → GENERAL. (마) 법령·영역 이름은 표시만 하고 유형을 바꾸지 않는다(확정은 A3 적용 법령)</li>
 * </ol>
 */
public final class IntakeJudge {
    public static final String GENERAL = "GENERAL", SPECIFIC = "SPECIFIC", OUT_OF_SCOPE = "OUT_OF_SCOPE";
    public static final String BREACH = "BREACH", DATA_SUBJECT_REQUEST = "DATA_SUBJECT_REQUEST";
    public static final String RULE = "04 4.3 결정 14";

    private IntakeJudge() {}

    /** intakeType = cases.intake_type, outOfScopeKind = cases.out_of_scope_kind(범위 밖일 때만). */
    public record Decision(String intakeType, String outOfScopeKind, String reason, List<String> lawNames,
                           Map<String, Integer> signalCounts) {
        public boolean outOfScope() {
            return OUT_OF_SCOPE.equals(intakeType);
        }
    }

    public static Decision judge(IntakeSignals s) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String k : List.of(IntakeSignals.OWN_CASE, IntakeSignals.ACTION, IntakeSignals.INCIDENT,
                IntakeSignals.RIGHTS_REQUEST, IntakeSignals.LAW_AREA)) {
            counts.put(k, (int) s.signals().stream().filter(x -> k.equals(x.kind())).count());
        }
        List<String> laws = new ArrayList<>();
        s.signals().stream().filter(x -> IntakeSignals.LAW_AREA.equals(x.kind()))
                .map(x -> x.name() != null && !x.name().isBlank() ? x.name().strip() : x.quote())
                .distinct().forEach(laws::add);
        boolean own = s.has(IntakeSignals.OWN_CASE), act = s.has(IntakeSignals.ACTION);
        if (s.has(IntakeSignals.INCIDENT)) {
            return new Decision(OUT_OF_SCOPE, BREACH, "(다) 사고 신호 → 범위 밖 이관"
                    + (s.has(IntakeSignals.RIGHTS_REQUEST) ? "(권리 요청 신호도 있음)" : ""), laws, counts);
        }
        if (s.has(IntakeSignals.RIGHTS_REQUEST)) {
            return new Decision(OUT_OF_SCOPE, DATA_SUBJECT_REQUEST, "(라) 실제 권리 요청 신호 → 범위 밖 이관", laws, counts);
        }
        if (own) {
            return new Decision(SPECIFIC, null, act ? "(가) 자기 사안 + (나) 처리 행위" : "(가) 자기 사안", laws, counts);
        }
        if (act) {
            return new Decision(SPECIFIC, null, "(나) 처리 행위만(자기 사안 신호 없음) → 애매하면 구체", laws, counts);
        }
        return new Decision(GENERAL, null, laws.isEmpty() ? "신호 없음" : "(마) 법령·영역 신호만(표시만)", laws, counts);
    }
}
