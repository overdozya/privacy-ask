package io.github.overdozya.privacyask.facts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.overdozya.privacyask.knowledge.Catalog;

/**
 * A1 입력에 넣는 사실 기준표(06 4.5 "지식은 프롬프트 파일에 적지 않는다", 6.2 캐시). 스냅샷에서 한 번 만든다.
 * 빼는 것: 원장 사실(보기가 MATCH·NO_MATCH = L1~L4, A3가 원장에서 찾음), '잘 모르겠음'류 보기(UNKNOWN: 에이전트가 미확인을 고르지 않음, 04 1.0).
 */
public final class FactSheet {
    private final List<Map<String, Object>> entries;

    private FactSheet(List<Map<String, Object>> entries) {
        this.entries = List.copyOf(entries);
    }

    public static FactSheet of(Catalog catalog) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Catalog.Fact f : catalog.facts()) {
            if (ledgerOnly(f)) {
                continue;
            }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("fact", f.factId());
            e.put("slot", f.slot());
            e.put("common", f.common());
            e.put("multi", f.multi());
            e.put("question", f.question());
            List<Map<String, Object>> opts = new ArrayList<>();
            for (Catalog.Option o : f.options()) {
                if ("UNKNOWN".equals(o.kind())) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("label", o.label());
                if ("DETAIL".equals(o.kind())) {
                    m.put("detail", true);
                }
                opts.add(m);
            }
            e.put("options", opts);
            out.add(e);
        }
        return new FactSheet(out);
    }

    /** 원장에서만 정하는 사실(L1~L4): 보기가 원장 일치 종류뿐. */
    public static boolean ledgerOnly(Catalog.Fact f) {
        return !f.options().isEmpty()
                && f.options().stream().allMatch(o -> "MATCH".equals(o.kind()) || "NO_MATCH".equals(o.kind()));
    }

    public List<Map<String, Object>> entries() {
        return entries;
    }

    public int size() {
        return entries.size();
    }
}
