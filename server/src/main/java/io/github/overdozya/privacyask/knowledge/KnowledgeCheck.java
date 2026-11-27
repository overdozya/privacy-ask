package io.github.overdozya.privacyask.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/**
 * 기동 검사(06 3.1). 하나라도 어기면 기동을 멈춘다.
 * ① 행 수 = manifest ② build_info·catalog_sources 셋·꾸러미 지금 판 하나 ③ 판 맞춤(규칙 세트 = 기준표 = 규칙 행, 슬롯 변환 판)
 * ④ 파일 사이 참조(꾸러미 단위·대상 → kb, 조건식의 사실·라벨·상태·슬롯·값 → 기준표, 슬롯 변환·규칙 → 기준표, 근거 키 → kb).
 */
public final class KnowledgeCheck {
    private KnowledgeCheck() {}

    /** manifestRows가 null이면(공개 레포만 받은 경우) ①을 건너뛴다. */
    public static List<String> check(KnowledgeSnapshot s, Map<String, Long> manifestRows) {
        List<String> bad = new ArrayList<>();
        if (manifestRows != null) {
            for (var e : manifestRows.entrySet()) {
                Long got = s.stats().rows().get(e.getKey());
                if (got == null) {
                    bad.add("manifest의 " + e.getKey() + "를 스냅샷이 읽지 않음");
                } else if (got.longValue() != e.getValue().longValue()) {
                    bad.add(e.getKey() + " 행 수 " + got + " ≠ manifest " + e.getValue());
                }
            }
            for (String t : s.stats().rows().keySet()) {
                if (!manifestRows.containsKey(t)) {
                    bad.add(t + "가 manifest에 없음");
                }
            }
        }
        heads(s, bad);
        packages(s, bad);
        rules(s, bad);
        return bad;
    }

    private static void heads(KnowledgeSnapshot s, List<String> bad) {
        if (s.buildInfo().isEmpty()) {
            bad.add("build_info가 비어 있음");
        }
        for (String n : List.of(Catalog.RULE_SET, Catalog.FACT_MAP, Catalog.SLOT_RULES)) {
            if (s.catalog().source(n).isEmpty()) {
                bad.add("catalog_sources에 " + n + " 없음");
            }
        }
        Packages p = s.packages();
        if (p.currentVersion() == null) {
            bad.add("꾸러미 지금 판(is_current=1)이 하나가 아님");
        } else {
            p.inVersion(p.currentVersion()).stream().filter(x -> !x.current())
                    .forEach(x -> bad.add("지금 판 " + x.version() + "의 " + x.packageId() + "가 is_current=0"));
            String bi = s.buildInfo().get("package_current");
            if (bi != null && !bi.equals(p.currentVersion())) {
                bad.add("build_info package_current " + bi + " ≠ 꾸러미 지금 판 " + p.currentVersion());
            }
        }
        String rsv = s.catalog().ruleSetVersion();
        if (s.ruleSet().version() == null) {
            bad.add("rule_sets가 비어 있음");
        } else if (rsv != null && !rsv.equals(s.ruleSet().version())) {
            bad.add("규칙 세트 판 " + s.ruleSet().version() + " ≠ catalog_sources " + rsv);
        }
        for (Catalog.Slot sl : s.catalog().slots()) {
            if (!sl.ruleSetVersion().equals(s.ruleSet().version())) {
                bad.add("slot_catalog " + sl.slotKey() + " rule_set_version " + sl.ruleSetVersion() + " ≠ 규칙 세트 "
                        + s.ruleSet().version());
            }
        }
        if (s.ruleSet().rules().isEmpty()) {
            bad.add("risk_rules가 비어 있음");
        }
        String srv = s.catalog().source(Catalog.SLOT_RULES).map(Catalog.Source::version).orElse(null);
        if (s.slotRules().version() == null) {
            bad.add("slot_rules가 비어 있음");
        } else if (srv != null && !srv.equals(s.slotRules().version())) {
            bad.add("슬롯 변환 판 " + s.slotRules().version() + " ≠ catalog_sources " + srv);
        }
        for (Catalog.Fact f : s.catalog().facts()) {
            for (String a : f.ledgerAttachments()) {
                if (!s.ledger().attachments().contains(a)) {
                    bad.add("사실 " + f.factId() + "의 원장 첨부 " + a + "가 ledger_versions에 없음");
                }
            }
        }
    }

    private static void packages(KnowledgeSnapshot s, List<String> bad) {
        for (String v : s.packages().versions()) {
            for (Packages.Package p : s.packages().inVersion(v)) {
                String where = v + " " + p.packageId();
                // 조건식의 값 검사는 지금 판만: 지난 판(v1, 고정)은 그때 기준표 값을 쓴다(v1 PKG14 '추가적 이용·제공').
                // 사안은 시작 때 지금 판을 고정하므로 지난 판 조건식은 실행에서 쓰이지 않는다. 원문 참조는 모든 판을 본다.
                boolean current = v.equals(s.packages().currentVersion());
                if (current) {
                    conditions(s, p, where, bad);
                }
                items(s, p, where, bad);
            }
        }
    }

    private static void conditions(KnowledgeSnapshot s, Packages.Package p, String where, List<String> bad) {
        String v = p.version();
        Set<String> interpIds = new java.util.HashSet<>();
        p.conditions().forEach(c -> c.path("interp").forEach(i -> interpIds.add(i.path("id").asString())));
        {
            {
                cond(s, p.trigger(), where + " trigger", bad);
                for (JsonNode c : p.conditions()) {
                    String cw = where + "." + c.path("id").asString();
                    cond(s, c.path("when"), cw + " when", bad);
                    for (JsonNode f : c.path("facts")) {
                        cond(s, f, cw + " facts", bad);
                    }
                    for (JsonNode i : c.path("interp")) {
                        String iw = cw + "." + i.path("id").asString();
                        for (JsonNode f : i.path("facts")) {
                            fact(s, f.asString(), iw + " facts", bad);
                        }
                        cond(s, i.path("on_when"), iw + " on_when", bad);
                        // off_when은 사람이 읽는 설명 글이다. 기계가 읽는 끄기는 조건의 interp_off(아래)
                        if (i.has("off_when") && !i.get("off_when").isString()) {
                            cond(s, i.get("off_when"), iw + " off_when", bad);
                        }
                        String b = i.path("basis").asString(null);
                        if (b != null && !lawKey(s, b)) {
                            bad.add(iw + " 근거 키 " + b + "가 KB에 없음");
                        }
                    }
                }
                for (JsonNode c : p.conditions()) {
                    for (JsonNode off : c.path("interp_off")) {
                        if (!interpIds.contains(off.asString())) {
                            bad.add(where + "." + c.path("id").asString() + " interp_off " + off.asString() + "가 꾸러미에 없음");
                        }
                    }
                }
                for (String ob : p.openedBy()) {
                    String[] parts = ob.split("\\.", 2);
                    var other = s.packages().get(v, parts[0]);
                    if (other.isEmpty() || parts.length < 2 || !other.get().conditionIds().contains(parts[1])) {
                        bad.add(where + " opened_by " + ob + "가 없음");
                    }
                }
            }
        }
    }

    private static void items(KnowledgeSnapshot s, Packages.Package p, String where, List<String> bad) {
        {
            {
                for (Packages.Item it : p.items()) {
                    String w = where + " 항목 " + it.itemId();
                    if (s.documents().get(it.targetDocId()).isEmpty()) {
                        bad.add(w + " target_doc_id " + it.targetDocId() + " 없음");
                    }
                    boolean target = switch (it.targetKind()) {
                        case "LAW_UNIT" -> s.laws().hasKeyOrArticle(it.targetKey());
                        case "ANNEX" -> s.annexes().has(it.targetKey());
                        case "GUIDE_SECTION" -> s.sections().has(it.targetKey());
                        default -> false;
                    };
                    if (!target) {
                        bad.add(w + " 대상 " + it.targetKind() + " " + it.targetKey() + " 없음");
                    }
                    for (String c : it.conds()) {
                        if (!p.conditionIds().contains(c)) {
                            bad.add(w + " 조건 " + c + "가 꾸러미에 없음");
                        }
                    }
                    for (Packages.Unit u : it.units()) {
                        String uw = w + " 단위 " + u.ord();
                        if ((u.chunkId() == null) == (u.annexId() == null)) {
                            bad.add(uw + ": chunk_id·annex_id 중 하나만 있어야 함");
                        }
                        if (u.chunkId() != null) {
                            var c = s.laws().byChunkId(u.chunkId());
                            if (c.isEmpty()) {
                                bad.add(uw + " chunk_id " + u.chunkId() + " 없음");
                            } else if (!c.get().unitKey().equals(u.unitKey())) {
                                bad.add(uw + " chunk " + u.chunkId() + " 키 " + c.get().unitKey() + " ≠ " + u.unitKey());
                            }
                        }
                        if (u.annexId() != null) {
                            var a = s.annexes().byId(u.annexId());
                            if (a.isEmpty()) {
                                bad.add(uw + " annex_id " + u.annexId() + " 없음");
                            } else if (!a.get().annexKey().equals(u.unitKey())) {
                                bad.add(uw + " annex " + u.annexId() + " 키 " + a.get().annexKey() + " ≠ " + u.unitKey());
                            }
                        }
                    }
                }
            }
        }
    }

    private static void rules(KnowledgeSnapshot s, List<String> bad) {
        for (var e : s.slotRules().bySlot().entrySet()) {
            var slot = s.catalog().slot(e.getKey());
            for (SlotRules.Rule r : e.getValue()) {
                String w = "slot_rules " + r.slotKey() + "#" + r.ord();
                if (slot.isEmpty()) {
                    bad.add(w + ": 슬롯 없음");
                    continue;
                }
                cond(s, r.when(), w, bad);
                for (String v : r.values()) {
                    if (!slot.get().values().contains(v)) {
                        bad.add(w + " 값 " + v + "가 슬롯 값에 없음");
                    }
                }
                for (String k : r.basisKeys()) {
                    if (!lawKey(s, k)) {
                        bad.add(w + " 근거 키 " + k + "가 KB에 없음");
                    }
                }
            }
        }
        for (RuleSet.RiskRule r : s.ruleSet().rules()) {
            cond(s, r.condition(), "risk_rules " + r.ruleId(), bad);
        }
        for (Catalog.Slot sl : s.catalog().slots()) {
            cond(s, sl.askCondition(), "slot_catalog " + sl.slotKey() + " ask_condition", bad);
        }
    }

    private static boolean lawKey(KnowledgeSnapshot s, String k) {
        return s.laws().hasKeyOrArticle(k) || s.annexes().has(k) || s.sections().has(k);
    }

    private static void fact(KnowledgeSnapshot s, String factId, String where, List<String> bad) {
        if (s.catalog().fact(factId).isEmpty()) {
            bad.add(where + ": 사실 " + factId + "가 기준표에 없음");
        }
    }

    private static final Set<String> OPS = Set.of("eq", "in", "contains", "contains_any", "status");

    /** 조건식 원자(fact·slot·ledger)와 결합(all·any·not)을 훑어 기준표에 있는지 본다. 비었거나 없으면 통과. */
    static void cond(KnowledgeSnapshot s, JsonNode n, String where, List<String> bad) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return;
        }
        if (n.isArray()) {
            for (JsonNode x : n) {
                cond(s, x, where, bad);
            }
            return;
        }
        if (!n.isObject()) {
            bad.add(where + ": 조건식이 객체가 아님");
            return;
        }
        if (n.has("all") || n.has("any")) {
            cond(s, n.has("all") ? n.get("all") : n.get("any"), where, bad);
            return;
        }
        if (n.has("not")) {
            cond(s, n.get("not"), where, bad);
            return;
        }
        String op = n.path("op").asString("");
        if (!OPS.contains(op)) {
            bad.add(where + ": 연산자 " + op);
            return;
        }
        List<String> values = new ArrayList<>();
        JsonNode v = n.path("value");
        if (v.isArray()) {
            v.forEach(x -> values.add(x.asString()));
        } else {
            values.add(v.asString());
        }
        if (n.has("fact")) {
            String id = n.get("fact").asString();
            var f = s.catalog().fact(id);
            if (f.isEmpty()) {
                bad.add(where + ": 사실 " + id + "가 기준표에 없음");
                return;
            }
            for (String x : values) {
                boolean ok = "status".equals(op) ? Catalog.STATUSES.contains(x) : f.get().hasOption(x);
                if (!ok) {
                    bad.add(where + ": " + id + " " + op + " '" + x + "'가 " + ("status".equals(op) ? "상태" : "보기") + "에 없음");
                }
            }
        } else if (n.has("slot")) {
            String k = n.get("slot").asString();
            var sl = s.catalog().slot(k);
            if (sl.isEmpty()) {
                bad.add(where + ": 슬롯 " + k + "가 기준표에 없음");
                return;
            }
            for (String x : values) {
                if (!sl.get().values().contains(x)) {
                    bad.add(where + ": 슬롯 " + k + " 값 '" + x + "'가 없음");
                }
            }
        } else if (n.has("ledger")) {
            String id = n.get("ledger").asString();
            if (s.catalog().fact(id).isEmpty() || s.catalog().fact(id).get().ledgerAttachments().isEmpty()) {
                bad.add(where + ": 원장 사실 " + id + "가 기준표에 없음");
            }
            if (n.path("column").asString("").isBlank()) {
                bad.add(where + ": 원장 칸 이름 없음");
            }
        } else {
            bad.add(where + ": 원자가 fact·slot·ledger가 아님");
        }
    }
}
