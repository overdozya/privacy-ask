package io.github.overdozya.privacyask.rules;

import java.util.List;

/**
 * 조건식 하나(06 3.2)가 보는 것: 사실 판, 같은 흐름에서 먼저 계산한 슬롯, MATCHED L 사실이 기댄 원장 행의 칸 글.
 */
public interface EvalContext {

    /** 슬롯 원자가 보는 값. status: CONFIRMED · PARTIAL · UNKNOWN · SKIPPED · OPEN. */
    record SlotView(String status, List<String> values) {
        public SlotView {
            values = List.copyOf(values == null ? List.of() : values);
        }

        /** 06 3.2: CONFIRMED·PARTIAL이면 아는 값으로 보고, 그 밖은 원자가 거짓. */
        public boolean usable() {
            return "CONFIRMED".equals(status) || "PARTIAL".equals(status);
        }
    }

    /** 원장 행의 칸 하나(칸 이름, 글). */
    record Column(String name, String text) {}

    /** 없는 사실은 FactState.ABSENT(NOT_FOUND). */
    FactState fact(String factId);

    /** 아직 계산하지 않은 슬롯이면 IllegalStateException(계산 순서 잘못). */
    SlotView slot(String slotKey);

    /** 원장 행 칸들. 없는 행이면 IllegalStateException. */
    List<Column> ledgerRow(String rowId);
}
