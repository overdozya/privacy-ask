package io.github.overdozya.privacyask.issues;

import java.util.List;

import io.github.overdozya.privacyask.rules.EvalContext;

/**
 * 흐름 하나의 슬롯 하나(06 3.2 = case_judgment_slots 칸 + 해석기 안 상태).
 * status: CONFIRMED · PARTIAL · UNKNOWN · SKIPPED · OPEN. OPEN은 "아직 물을 사실이 남음"이라 해석기·A2 안에서만 쓴다
 * (case_judgment_slots CHECK 밖, 판정 행에 적는 법은 S9).
 * basisFacts·unknownFacts·openFacts는 기준표(fact_catalog.ord) 순, flags는 규칙 순, fired는 정하거나 더한 규칙의 ord.
 */
public record SlotResult(String status, List<String> values, List<String> basisFacts, List<String> unknownFacts,
                         List<String> openFacts, List<String> flags, List<Integer> fired) {
    public static final String CONFIRMED = "CONFIRMED", PARTIAL = "PARTIAL", UNKNOWN = "UNKNOWN",
            SKIPPED = "SKIPPED", OPEN = "OPEN";

    public SlotResult {
        values = List.copyOf(values);
        basisFacts = List.copyOf(basisFacts);
        unknownFacts = List.copyOf(unknownFacts);
        openFacts = List.copyOf(openFacts);
        flags = List.copyOf(flags);
        fired = List.copyOf(fired);
    }

    /** A2가 슬롯 값을 가정해 볼 때(06 3.5 보조 계산). */
    public static SlotResult forced(String status, List<String> values) {
        return new SlotResult(status, values, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public EvalContext.SlotView view() {
        return new EvalContext.SlotView(status, values);
    }

    /** 현업 미확인이 걸린 슬롯(04 1.9 → 이관 사유 REQUESTER_UNKNOWN). */
    public boolean hasUnknown() {
        return !unknownFacts.isEmpty();
    }
}
