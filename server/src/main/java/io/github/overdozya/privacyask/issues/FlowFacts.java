package io.github.overdozya.privacyask.issues;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import io.github.overdozya.privacyask.rules.EvalContext;
import io.github.overdozya.privacyask.rules.FactState;

/**
 * 흐름 하나의 지금 사실(공통 사실 포함, 흐름 사실이 공통 판을 가림)과 원장 행 칸 조회.
 * 없는 사실은 NOT_FOUND. A2는 with로 사실 하나를 바꾼 판을 만들어 다시 계산한다(06 3.5).
 */
public record FlowFacts(int flowNo, Map<String, FactState> facts, Function<String, List<EvalContext.Column>> ledger) {

    public FlowFacts {
        facts = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(facts));
    }

    public FactState fact(String factId) {
        return facts.getOrDefault(factId, FactState.ABSENT);
    }

    public FlowFacts with(String factId, FactState state) {
        Map<String, FactState> m = new LinkedHashMap<>(facts);
        m.put(factId, state);
        return new FlowFacts(flowNo, m, ledger);
    }

    public List<EvalContext.Column> ledgerRow(String rowId) {
        List<EvalContext.Column> cols = ledger == null ? null : ledger.apply(rowId);
        if (cols == null) {
            throw new IllegalStateException("원장 행 글이 없음: " + rowId);
        }
        return cols;
    }
}
