package io.github.overdozya.privacyask.issues;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.overdozya.privacyask.knowledge.Catalog;
import io.github.overdozya.privacyask.knowledge.SlotRules;
import io.github.overdozya.privacyask.rules.Condition;
import io.github.overdozya.privacyask.rules.EvalContext;
import io.github.overdozya.privacyask.rules.FactState;
import io.github.overdozya.privacyask.rules.Tri;

/**
 * 슬롯 변환(06 3.2 SlotConverter): 흐름 하나의 지금 사실 → 현업 슬롯 8개. 규칙은 DB slot_rules(S3 슬롯변환_v1)이고,
 * 뜻은 그 파일의 '의미' 절과 build/case/slot_rules.py(eval_slot·evaluate)와 같다. 같은 입력이면 늘 같은 결과.
 *
 * <ul>
 *   <li>단일 슬롯: ord 순으로 처음 참인 SET·SKIP. 그 앞 규칙의 모름 사실 중 미확인(UNKNOWN)이 있으면 UNKNOWN(결정 5:
 *       앞 규칙이 더 높은 등급일 수 있음), 열림(NOT_FOUND)뿐이면 뒤 규칙으로 정하고 openFacts에 남긴다.
 *       참이 없으면 미확인이 있으면 UNKNOWN, 아니면 OPEN.</li>
 *   <li>복수 슬롯: 참인 ADD를 모두 합친다. 미확인이 있으면 값이 있으면 PARTIAL·없으면 UNKNOWN(결정 2).</li>
 *   <li>계산 순서: 슬롯 원자가 가리키는 슬롯을 먼저, 같은 단계는 sort_order.</li>
 * </ul>
 */
public final class SlotConverter {

    /** 사실 하나의 기준표 칸: 순서와 대응표 슬롯 칸(근거 사실 고르기). */
    public record FactDef(String factId, int ord, Set<String> slots) {}

    /** 슬롯 하나: 복수 여부, 기준표 순서, 변환 규칙(ord 순). */
    public record SlotDef(String slotKey, boolean multi, int sortOrder, List<SlotRules.Rule> rules) {}

    private final Map<String, SlotDef> slots;     // sort_order 순
    private final Map<String, FactDef> facts;
    private final List<String> order;              // 계산 순서

    public SlotConverter(Collection<SlotDef> slotDefs, Collection<FactDef> factDefs) {
        Map<String, SlotDef> s = new LinkedHashMap<>();
        slotDefs.stream().sorted(Comparator.comparingInt(SlotDef::sortOrder)).forEach(d -> s.put(d.slotKey(), d));
        Map<String, FactDef> f = new LinkedHashMap<>();
        factDefs.forEach(d -> f.put(d.factId(), d));
        this.slots = java.util.Collections.unmodifiableMap(s);
        this.facts = java.util.Collections.unmodifiableMap(f);
        this.order = evalOrder(s);
    }

    public static SlotConverter of(Catalog catalog, SlotRules rules) {
        List<SlotDef> sd = new ArrayList<>();
        for (Catalog.Slot s : catalog.slots()) {
            sd.add(new SlotDef(s.slotKey(), s.multi(), s.sortOrder(), rules.forSlot(s.slotKey())));
        }
        List<FactDef> fd = new ArrayList<>();
        for (Catalog.Fact x : catalog.facts()) {
            fd.add(new FactDef(x.factId(), x.ord(), Set.of(x.slot().split("·"))));
        }
        return new SlotConverter(sd, fd);
    }

    /** 계산 순서(S3 결과: data_categories → purpose_scope → transfers → legal_basis → …). */
    public List<String> order() {
        return order;
    }

    /** 슬롯 키, 기준표 sort_order 순. */
    public List<String> slotKeys() {
        return List.copyOf(slots.keySet());
    }

    public Map<String, SlotDef> slots() {
        return slots;
    }

    public Map<String, SlotResult> convert(FlowFacts flow) {
        return convert(flow, Map.of());
    }

    /**
     * forced: 계산하지 않고 이 결과로 둘 슬롯(A2 보조 계산: "이 슬롯이 이 값이면"). 뒤에 계산하는 슬롯은 이 값을 본다.
     * 결과는 sort_order 순.
     */
    public Map<String, SlotResult> convert(FlowFacts flow, Map<String, SlotResult> forced) {
        Map<String, SlotResult> done = new LinkedHashMap<>();
        Ctx ctx = new Ctx(flow, done);
        for (String sk : order) {
            SlotResult f = forced.get(sk);
            done.put(sk, f != null ? f : evalSlot(slots.get(sk), ctx));
        }
        Map<String, SlotResult> out = new LinkedHashMap<>();
        for (String sk : slots.keySet()) {
            out.put(sk, done.get(sk));
        }
        return out;
    }

    // ───────── 슬롯 하나 ─────────

    private SlotResult evalSlot(SlotDef def, Ctx ctx) {
        boolean multi = def.multi();
        List<SlotRules.Rule> seen = new ArrayList<>();
        List<SlotRules.Rule> trueRules = new ArrayList<>();
        Set<String> pending = new LinkedHashSet<>();
        SlotRules.Rule decided = null;
        for (SlotRules.Rule r : def.rules()) {
            Condition.Result res = Condition.eval(r.when(), ctx);
            seen.add(r);
            if (res.value() == Tri.UNKNOWN) {
                pending.addAll(res.unknownFacts());
                continue;
            }
            if (res.value() == Tri.TRUE) {
                trueRules.add(r);
                if ("SKIP".equals(r.effect()) || !multi) {
                    decided = r;
                    break;
                }
            }
        }
        Set<String> unknown = new LinkedHashSet<>();
        Set<String> opened = new LinkedHashSet<>();
        for (String f : pending) {
            (FactState.UNKNOWN.equals(ctx.fact(f).status()) ? unknown : opened).add(f);
        }
        String status;
        List<String> values = new ArrayList<>();
        List<String> flags = new ArrayList<>();
        List<Integer> fired = new ArrayList<>();
        if (decided != null && "SKIP".equals(decided.effect()) && unknown.isEmpty()) {
            status = SlotResult.SKIPPED;
            fired.add(decided.ord());
        } else if (!multi) {
            if (decided != null && unknown.isEmpty()) {
                status = SlotResult.CONFIRMED;
                values.addAll(decided.values());
                fired.add(decided.ord());
                if (decided.flag() != null) {
                    flags.add(decided.flag());
                }
            } else {
                status = unknown.isEmpty() ? SlotResult.OPEN : SlotResult.UNKNOWN;
            }
        } else {
            List<SlotRules.Rule> adds = trueRules.stream().filter(r -> "ADD".equals(r.effect())).toList();
            for (SlotRules.Rule r : adds) {
                fired.add(r.ord());
                for (String x : r.values()) {
                    if (!values.contains(x)) {
                        values.add(x);
                    }
                }
                if (r.flag() != null && !flags.contains(r.flag())) {
                    flags.add(r.flag());
                }
            }
            if (!unknown.isEmpty()) {
                status = values.isEmpty() ? SlotResult.UNKNOWN : SlotResult.PARTIAL;
            } else {
                status = (!values.isEmpty() || !adds.isEmpty()) ? SlotResult.CONFIRMED : SlotResult.OPEN;
            }
        }
        if (SlotResult.UNKNOWN.equals(status) || SlotResult.OPEN.equals(status)) {
            values.clear();
            flags.clear();
            fired.clear();
        }
        Set<String> basis = new HashSet<>();
        for (SlotRules.Rule r : seen) {
            for (String f : Condition.refFacts(r.when())) {
                FactDef fd = facts.get(f);
                if (!ctx.fact(f).notFound() && fd != null && fd.slots().contains(def.slotKey())) {
                    basis.add(f);
                }
            }
            if (fired.contains(r.ord())) {
                basis.addAll(Condition.support(r.when(), ctx, true));
            }
        }
        boolean withUnknown = SlotResult.UNKNOWN.equals(status) || SlotResult.PARTIAL.equals(status);
        return new SlotResult(status, values, byOrd(basis), withUnknown ? byOrd(unknown) : List.of(), byOrd(opened),
                flags, fired);
    }

    private List<String> byOrd(Collection<String> fs) {
        return fs.stream().distinct().sorted(Comparator.comparingInt(this::ordOf)).toList();
    }

    private int ordOf(String factId) {
        FactDef d = facts.get(factId);
        if (d == null) {
            throw new IllegalStateException("기준표에 없는 사실: " + factId);
        }
        return d.ord();
    }

    private static List<String> evalOrder(Map<String, SlotDef> slots) {
        Map<String, Set<String>> deps = new LinkedHashMap<>();
        slots.forEach((k, d) -> {
            Set<String> s = new HashSet<>();
            d.rules().forEach(r -> s.addAll(Condition.refSlots(r.when())));
            deps.put(k, s);
        });
        List<String> out = new ArrayList<>();
        Set<String> done = new HashSet<>();
        while (out.size() < deps.size()) {
            String next = null;
            for (Map.Entry<String, Set<String>> e : deps.entrySet()) {   // sort_order 순
                if (!done.contains(e.getKey()) && done.containsAll(e.getValue())) {
                    next = e.getKey();
                    break;
                }
            }
            if (next == null) {
                throw new IllegalStateException("슬롯 원자에 고리가 있음");
            }
            out.add(next);
            done.add(next);
        }
        return List.copyOf(out);
    }

    /** 변환 중의 조건식 문맥: 사실은 흐름 판, 슬롯은 이미 계산한 것. */
    private static final class Ctx implements EvalContext {
        private final FlowFacts flow;
        private final Map<String, SlotResult> done;

        Ctx(FlowFacts flow, Map<String, SlotResult> done) {
            this.flow = flow;
            this.done = done;
        }

        @Override
        public FactState fact(String factId) {
            return flow.fact(factId);
        }

        @Override
        public SlotView slot(String slotKey) {
            SlotResult r = done.get(slotKey);
            if (r == null) {
                throw new IllegalStateException("슬롯 " + slotKey + "을 아직 계산하지 않음");
            }
            return r.view();
        }

        @Override
        public List<Column> ledgerRow(String rowId) {
            return flow.ledgerRow(rowId);
        }
    }
}
