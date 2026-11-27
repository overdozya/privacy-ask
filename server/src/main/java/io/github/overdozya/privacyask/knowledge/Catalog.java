package io.github.overdozya.privacyask.knowledge;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;

/** 기준표: 사실·보기(04 대응표), 슬롯·슬롯 값(현업 규칙 세트), 기준 파일 판·해시(catalog_sources). */
public final class Catalog {
    public static final String FACT_MAP = "fact_map", RULE_SET = "rule_set", SLOT_RULES = "slot_rules";
    public static final List<String> STATUSES = List.of("CONFIRMED", "MATCHED", "INFERRED", "NOT_FOUND", "UNKNOWN");

    /** kind: CHOICE · DETAIL(자유 입력이 따라옴) · UNKNOWN('잘 모르겠음') · MATCH · NO_MATCH(원장 사실). */
    public record Option(int ord, String label, String kind) {}

    public record Fact(String factId, int ord, String slot, boolean common, String usage, String question,
                       String allowedRaw, boolean multi, String ledgerLookup, List<String> ledgerAttachments,
                       String onUnknown, String basisKeys, String interp, List<Option> options) {
        public Optional<Option> option(String label) {
            return options.stream().filter(o -> o.label().equals(label)).findFirst();
        }

        public boolean hasOption(String label) {
            return option(label).isPresent();
        }
    }

    public record Slot(String slotKey, String label, String valueType, JsonNode askCondition, String ruleSetVersion,
                       int sortOrder, List<String> values) {
        public boolean multi() {
            return "MULTI".equals(valueType);
        }
    }

    public record Source(String name, String path, String version, String sha256) {}

    private final Map<String, Fact> facts;   // fact_catalog.ord 순
    private final Map<String, Slot> slots;   // sort_order 순
    private final Map<String, Source> sources;

    Catalog(List<Fact> facts, List<Slot> slots, List<Source> sources) {
        Map<String, Fact> f = new LinkedHashMap<>();
        facts.stream().sorted(java.util.Comparator.comparingInt(Fact::ord)).forEach(x -> f.put(x.factId(), x));
        Map<String, Slot> s = new LinkedHashMap<>();
        slots.stream().sorted(java.util.Comparator.comparingInt(Slot::sortOrder)).forEach(x -> s.put(x.slotKey(), x));
        Map<String, Source> src = new LinkedHashMap<>();
        sources.forEach(x -> src.put(x.name(), x));
        this.facts = java.util.Collections.unmodifiableMap(f);
        this.slots = java.util.Collections.unmodifiableMap(s);
        this.sources = java.util.Collections.unmodifiableMap(src);
    }

    public Optional<Fact> fact(String factId) {
        return Optional.ofNullable(facts.get(factId));
    }

    public Collection<Fact> facts() {
        return facts.values();
    }

    public Optional<Slot> slot(String slotKey) {
        return Optional.ofNullable(slots.get(slotKey));
    }

    public Collection<Slot> slots() {
        return slots.values();
    }

    public Optional<Source> source(String name) {
        return Optional.ofNullable(sources.get(name));
    }

    public Collection<Source> sources() {
        return sources.values();
    }

    /** 판정 C12: 대응표 해시. */
    public String factMapSha256() {
        return source(FACT_MAP).map(Source::sha256).orElse(null);
    }

    public String ruleSetVersion() {
        return source(RULE_SET).map(Source::version).orElse(null);
    }

    public int optionCount() {
        return facts.values().stream().mapToInt(x -> x.options().size()).sum();
    }

    public int slotValueCount() {
        return slots.values().stream().mapToInt(x -> x.values().size()).sum();
    }
}
