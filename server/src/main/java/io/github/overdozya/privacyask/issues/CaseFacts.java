package io.github.overdozya.privacyask.issues;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import io.github.overdozya.privacyask.knowledge.Ledger;
import io.github.overdozya.privacyask.record.CaseState;
import io.github.overdozya.privacyask.rules.EvalContext;
import io.github.overdozya.privacyask.rules.FactState;

/** 사안 상태(CaseReader.load) → 흐름마다 지금 사실(공통 사실 + 그 흐름 사실, store.py resolve_fact와 같은 뜻). */
public final class CaseFacts {
    private CaseFacts() {}

    public static FactState state(CaseState.Fact f) {
        return new FactState(f.status(), f.labels(), f.ledgerRows(), f.sourceKind());
    }

    /** 원장 행 → 칸 목록. 없는 행이면 null(FlowFacts가 오류로 낸다). */
    public static Function<String, List<EvalContext.Column>> ledger(Ledger ledger) {
        return rowId -> ledger.row(rowId)
                .map(r -> r.fields().stream().map(c -> new EvalContext.Column(c.name(), c.text())).toList())
                .orElse(null);
    }

    public static Map<String, FactState> facts(CaseState state, Integer flowNo) {
        Map<String, FactState> m = new LinkedHashMap<>();
        List<CaseState.Fact> cur = state.currentFacts();
        cur.stream().filter(f -> f.flowNo() == null).forEach(f -> m.put(f.factId(), state(f)));
        if (flowNo != null) {
            cur.stream().filter(f -> Objects.equals(f.flowNo(), flowNo)).forEach(f -> m.put(f.factId(), state(f)));
        }
        return m;
    }

    /** 흐름 번호 순. 흐름이 없으면 빈 목록. */
    public static List<FlowFacts> flows(CaseState state, Ledger ledger) {
        Function<String, List<EvalContext.Column>> led = ledger(ledger);
        List<FlowFacts> out = new ArrayList<>();
        state.flows().stream().map(CaseState.Flow::flowNo).sorted()
                .forEach(n -> out.add(new FlowFacts(n, facts(state, n), led)));
        return out;
    }
}
