package io.github.overdozya.privacyask.knowledge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/** 슬롯 변환 규칙(slot_rules, S3 슬롯변환_v1). 슬롯마다 ord 순. 해석은 pa/issues의 SlotConverter(S5). */
public final class SlotRules {

    /** effect: SET(단일) · ADD(복수) · SKIP(묻지 않음 → SKIPPED). flag: null · FACT_CONFLICT · CHECK_ITEM. */
    public record Rule(String slotKey, int ord, JsonNode when, String effect, List<String> values, String flag,
                       List<String> basisKeys, String note) {}

    private final String version;
    private final Map<String, List<Rule>> bySlot;
    private final int size;

    SlotRules(String version, List<Rule> rules, List<String> slotOrder) {
        this.version = version;
        Map<String, List<Rule>> m = new LinkedHashMap<>();
        for (String s : slotOrder) {
            List<Rule> rs = rules.stream().filter(r -> r.slotKey().equals(s))
                    .sorted(java.util.Comparator.comparingInt(Rule::ord)).toList();
            if (!rs.isEmpty()) {
                m.put(s, rs);
            }
        }
        rules.stream().filter(r -> !m.containsKey(r.slotKey())).forEach(r -> m.computeIfAbsent(r.slotKey(),
                k -> rules.stream().filter(x -> x.slotKey().equals(k))
                        .sorted(java.util.Comparator.comparingInt(Rule::ord)).toList()));
        this.bySlot = java.util.Collections.unmodifiableMap(m);
        this.size = rules.size();
    }

    public String version() {
        return version;
    }

    public List<Rule> forSlot(String slotKey) {
        return bySlot.getOrDefault(slotKey, List.of());
    }

    public Map<String, List<Rule>> bySlot() {
        return bySlot;
    }

    public int size() {
        return size;
    }
}
