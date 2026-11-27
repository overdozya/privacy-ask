package io.github.overdozya.privacyask.rules;

import java.util.List;
import java.util.Set;

/**
 * 조건식이 보는 사실 하나의 지금 판(흐름 사실이면 그 흐름, 공통 사실은 사안).
 * status: CONFIRMED · MATCHED · INFERRED · NOT_FOUND · UNKNOWN. values: 보기 글(라벨), 판의 순서대로.
 * rows: MATCHED·INFERRED L 사실이 기댄 원장 행 ID. sourceKind: INQUIRY · ANSWER · LEDGER(없으면 null).
 */
public record FactState(String status, List<String> values, List<String> rows, String sourceKind) {
    public static final String CONFIRMED = "CONFIRMED", MATCHED = "MATCHED", INFERRED = "INFERRED",
            NOT_FOUND = "NOT_FOUND", UNKNOWN = "UNKNOWN";
    /** 값이 있는 상태(06 3.2 사실 원자). UNKNOWN·NOT_FOUND는 '모름'. */
    public static final Set<String> KNOWN = Set.of(CONFIRMED, MATCHED, INFERRED);
    public static final FactState ABSENT = new FactState(NOT_FOUND, List.of(), List.of(), null);

    public FactState {
        values = List.copyOf(values == null ? List.of() : values);
        rows = List.copyOf(rows == null ? List.of() : rows);
    }

    public static FactState of(String status, List<String> values) {
        return new FactState(status, values, List.of(), null);
    }

    public static FactState confirmed(String... values) {
        return of(CONFIRMED, List.of(values));
    }

    /** 현업이 '잘 모르겠음'을 고른 판(04 1.9 미확인). */
    public static FactState unknown(String label) {
        return of(UNKNOWN, List.of(label));
    }

    public boolean known() {
        return KNOWN.contains(status);
    }

    public boolean notFound() {
        return NOT_FOUND.equals(status);
    }
}
