package io.github.overdozya.privacyask.knowledge;

import java.lang.management.ManagementFactory;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.jdbc.core.simple.JdbcClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 지식 테이블 21개를 한 번씩 전부 읽어 KnowledgeSnapshot을 만든다. 읽은 행 수는 Stats.rows에 남기고,
 * manifest와의 비교·참조 검사는 KnowledgeCheck가 한다.
 */
public final class KnowledgeLoader {
    /** 적재 파일이 채우는 테이블(manifest.json tables와 같은 21개). */
    public static final List<String> TABLES = List.of(
            "kb_documents", "guide_sections", "kb_chunks", "kb_annexes", "kb_links", "build_info",
            "issue_packages", "package_items", "package_item_units",
            "ledger_versions", "ledger_rows", "ledger_segments", "ledger_unextracted",
            "catalog_sources", "fact_catalog", "fact_options", "slot_catalog", "slot_options",
            "rule_sets", "risk_rules", "slot_rules");

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public KnowledgeLoader(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** 테이블별 행 수만(적재 전인지 볼 때). */
    public Map<String, Long> countRows() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String t : TABLES) {
            out.put(t, jdbc.sql("SELECT COUNT(*) FROM `" + t + "`").query(Long.class).single());
        }
        return out;
    }

    public KnowledgeSnapshot load() {
        long heap0 = usedHeapAfterGc();
        long t0 = System.nanoTime();
        Map<String, Long> rows = new LinkedHashMap<>();

        Map<String, String> buildInfo = new TreeMap<>();
        jdbc.sql("SELECT k, v FROM build_info").query(rs -> {
            buildInfo.put(rs.getString(1), rs.getString(2));
        });
        rows.put("build_info", (long) buildInfo.size());

        List<Documents.Doc> docs = jdbc.sql("""
                SELECT doc_id, source_type, title, law_name, law_kind, stage, parent_doc_id, effective_date,
                       use_policy, use_scope, guide_version FROM kb_documents ORDER BY doc_id""")
                .query((rs, i) -> new Documents.Doc(rs.getInt("doc_id"), rs.getString("source_type"),
                        rs.getString("title"), rs.getString("law_name"), rs.getString("law_kind"), rs.getInt("stage"),
                        intOrNull(rs, "parent_doc_id"), date(rs, "effective_date"), rs.getString("use_policy"),
                        Packages.strings(node(rs.getString("use_scope"))), rs.getString("guide_version")))
                .list();
        rows.put("kb_documents", (long) docs.size());

        List<GuideSections.Section> sections = jdbc.sql("""
                SELECT section_id, doc_id, parent_id, depth, title, path_text, ord, num, is_leaf, page_from, page_to,
                       chunk_count, char_count FROM guide_sections""")
                .query((rs, i) -> new GuideSections.Section(rs.getString("section_id"), rs.getInt("doc_id"),
                        rs.getString("parent_id"), rs.getInt("depth"), rs.getString("title"),
                        rs.getString("path_text"), rs.getInt("ord"), rs.getString("num"), rs.getBoolean("is_leaf"),
                        intOrNull(rs, "page_from"), intOrNull(rs, "page_to"), rs.getInt("chunk_count"),
                        rs.getInt("char_count")))
                .list();
        rows.put("guide_sections", (long) sections.size());

        List<LawUnits.Unit> units = jdbc.sql("""
                SELECT chunk_id, doc_id, unit_key, article, title, status, effective_from, effective_to, replaced_by,
                       text, text_hash, section_id, page_from, page_to, chunk_key FROM kb_chunks ORDER BY chunk_id""")
                .query((rs, i) -> new LawUnits.Unit(rs.getInt("chunk_id"), rs.getInt("doc_id"),
                        rs.getString("unit_key"), rs.getString("article"), rs.getString("title"),
                        rs.getString("status"), date(rs, "effective_from"), date(rs, "effective_to"),
                        intOrNull(rs, "replaced_by"), rs.getString("text"), rs.getString("text_hash"),
                        rs.getString("section_id"), intOrNull(rs, "page_from"), intOrNull(rs, "page_to"),
                        rs.getString("chunk_key")))
                .list();
        rows.put("kb_chunks", (long) units.size());
        LawUnits laws = new LawUnits(units);

        List<Annexes.Annex> annexList = jdbc.sql("""
                SELECT annex_id, doc_id, annex_key, kind, annex_no, annex_sub, label, title, text, text_rows, status,
                       effective_from, text_hash FROM kb_annexes ORDER BY annex_id""")
                .query((rs, i) -> new Annexes.Annex(rs.getInt("annex_id"), rs.getInt("doc_id"),
                        rs.getString("annex_key"), rs.getString("kind"), rs.getInt("annex_no"),
                        rs.getInt("annex_sub"), rs.getString("label"), rs.getString("title"), rs.getString("text"),
                        rs.getString("text_rows"), rs.getString("status"), date(rs, "effective_from"),
                        rs.getString("text_hash")))
                .list();
        rows.put("kb_annexes", (long) annexList.size());

        List<KbLinks.Link> links = jdbc.sql("""
                SELECT link_id, link_type, src_key, src_chunk_id, src_annex_id, dst_key, dst_doc_id, dst_chunk_id,
                       dst_annex_id, resolution, unresolved_reason, evidence, origin FROM kb_links ORDER BY link_id""")
                .query((rs, i) -> new KbLinks.Link(rs.getInt("link_id"), rs.getString("link_type"),
                        rs.getString("src_key"), intOrNull(rs, "src_chunk_id"), intOrNull(rs, "src_annex_id"),
                        rs.getString("dst_key"), intOrNull(rs, "dst_doc_id"), intOrNull(rs, "dst_chunk_id"),
                        intOrNull(rs, "dst_annex_id"), rs.getString("resolution"), rs.getString("unresolved_reason"),
                        rs.getString("evidence"), rs.getString("origin")))
                .list();
        rows.put("kb_links", (long) links.size());

        Packages packages = loadPackages(rows);
        Ledger ledger = loadLedger(rows);
        Catalog catalog = loadCatalog(rows);
        RuleSet ruleSet = loadRuleSet(rows);
        SlotRules slotRules = loadSlotRules(rows, catalog);

        GuideSections guide = new GuideSections(sections, laws);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        long heap1 = usedHeapAfterGc();
        Map<String, Long> ordered = new LinkedHashMap<>();
        for (String t : TABLES) {
            ordered.put(t, rows.getOrDefault(t, 0L));
        }
        return new KnowledgeSnapshot(Map.copyOf(buildInfo), new Documents(docs), laws, new Annexes(annexList), guide,
                new KbLinks(links), packages, ledger, catalog, ruleSet, slotRules,
                new KnowledgeSnapshot.Stats(java.util.Collections.unmodifiableMap(ordered), ms, heap0, heap1,
                        Instant.now()));
    }

    private Packages loadPackages(Map<String, Long> rows) {
        Map<Integer, List<Packages.Unit>> unitsByItem = new HashMap<>();
        long[] nUnits = {0};
        jdbc.sql("SELECT item_id, ord, unit_key, chunk_id, annex_id FROM package_item_units ORDER BY item_id, ord")
                .query(rs -> {
                    nUnits[0]++;
                    unitsByItem.computeIfAbsent(rs.getInt("item_id"), k -> new ArrayList<>()).add(new Packages.Unit(
                            rs.getInt("ord"), rs.getString("unit_key"), intOrNull(rs, "chunk_id"),
                            intOrNull(rs, "annex_id")));
                });
        rows.put("package_item_units", nUnits[0]);
        Map<String, List<Packages.Item>> itemsByPkg = new HashMap<>();
        long[] nItems = {0};
        jdbc.sql("""
                SELECT item_id, version, package_id, ord, target_kind, target_key, target_doc_id, role, reason, source,
                       conds, answer_basis, basis_note, unit_count, char_count FROM package_items
                ORDER BY version, package_id, ord, item_id""").query(rs -> {
            nItems[0]++;
            int id = rs.getInt("item_id");
            Packages.Item it = new Packages.Item(id, rs.getString("version"), rs.getString("package_id"),
                    rs.getInt("ord"), rs.getString("target_kind"), rs.getString("target_key"),
                    rs.getInt("target_doc_id"), rs.getString("role"), rs.getString("reason"), rs.getString("source"),
                    Packages.strings(node(rs.getString("conds"))), rs.getBoolean("answer_basis"),
                    rs.getString("basis_note"), rs.getInt("unit_count"), rs.getInt("char_count"),
                    List.copyOf(unitsByItem.getOrDefault(id, List.of())));
            itemsByPkg.computeIfAbsent(it.version() + "\u0000" + it.packageId(), k -> new ArrayList<>()).add(it);
        });
        rows.put("package_items", nItems[0]);
        List<Packages.Package> pkgs = jdbc.sql("""
                SELECT version, package_id, is_current, issue_name, origin, trigger_json, trigger_status, opened_by,
                       trigger_note, rules_json, conditions_json, excluded_json, item_count, unit_count, total_chars,
                       status, content_sha256 FROM issue_packages ORDER BY version, package_id""")
                .query((rs, i) -> {
                    JsonNode conds = node(rs.getString("conditions_json"));
                    Set<String> cids = new LinkedHashSet<>(), interp = new LinkedHashSet<>();
                    for (JsonNode c : conds) {
                        cids.add(c.path("id").asString());
                        for (JsonNode x : c.path("interp")) {
                            if ("해석 쟁점".equals(x.path("status").asString(""))) {
                                interp.add(x.path("id").asString());
                            }
                        }
                    }
                    String v = rs.getString("version"), id = rs.getString("package_id");
                    return new Packages.Package(v, id, rs.getBoolean("is_current"), rs.getString("issue_name"),
                            rs.getString("origin"), node(rs.getString("trigger_json")), rs.getString("trigger_status"),
                            Packages.strings(node(rs.getString("opened_by"))), rs.getString("trigger_note"),
                            Packages.strings(node(rs.getString("rules_json"))), conds,
                            node(rs.getString("excluded_json")), rs.getInt("item_count"), rs.getInt("unit_count"),
                            rs.getInt("total_chars"), rs.getString("status"), rs.getString("content_sha256"),
                            Set.copyOf(cids), Set.copyOf(interp),
                            List.copyOf(itemsByPkg.getOrDefault(v + "\u0000" + id, List.of())));
                }).list();
        rows.put("issue_packages", (long) pkgs.size());
        Set<String> currentVersions = new LinkedHashSet<>();
        pkgs.stream().filter(Packages.Package::current).forEach(p -> currentVersions.add(p.version()));
        String current = currentVersions.size() == 1 ? currentVersions.iterator().next() : null;
        return new Packages(pkgs, current);
    }

    private Ledger loadLedger(Map<String, Long> rows) {
        List<Ledger.Version> versions = jdbc.sql("""
                SELECT version_id, attachment, published_on, title, file_name, file_sha256, page_count
                FROM ledger_versions ORDER BY version_id, attachment""")
                .query((rs, i) -> new Ledger.Version(rs.getString("version_id"), rs.getString("attachment"),
                        date(rs, "published_on"), rs.getString("title"), rs.getString("file_name"),
                        rs.getString("file_sha256"), rs.getInt("page_count")))
                .list();
        rows.put("ledger_versions", (long) versions.size());
        Map<String, List<Ledger.Segment>> segs = new HashMap<>();
        long[] nSeg = {0};
        jdbc.sql("""
                SELECT segment_id, row_id, col_name, col_kind, seg_type, seq, parent_id, term, source_id, text
                FROM ledger_segments ORDER BY row_id, seq, segment_id""").query(rs -> {
            nSeg[0]++;
            segs.computeIfAbsent(rs.getString("row_id"), k -> new ArrayList<>()).add(new Ledger.Segment(
                    rs.getString("segment_id"), rs.getString("col_name"), rs.getString("col_kind"),
                    rs.getString("seg_type"), rs.getInt("seq"), rs.getString("parent_id"), rs.getString("term"),
                    rs.getString("source_id"), rs.getString("text")));
        });
        rows.put("ledger_segments", nSeg[0]);
        List<Ledger.Row> lrows = jdbc.sql("""
                SELECT row_id, version_id, attachment, seq, table_no, table_title, service, service_key, fields_json,
                       col_kinds_json, pages_json, first_page, merged_json, flags_json FROM ledger_rows ORDER BY row_id""")
                .query((rs, i) -> {
                    String fj = rs.getString("fields_json");
                    List<Ledger.Field> fields = new ArrayList<>();
                    for (JsonNode pair : node(fj)) {
                        fields.add(new Ledger.Field(pair.get(0).asString(), pair.get(1).asString()));
                    }
                    String key = rs.getString("service_key");
                    return new Ledger.Row(rs.getString("row_id"), rs.getString("version_id"),
                            rs.getString("attachment"), rs.getInt("seq"), rs.getInt("table_no"),
                            rs.getString("table_title"), rs.getString("service"), key, Ledger.splitServiceKey(key),
                            fj, List.copyOf(fields), Hashes.sha256(fj), node(rs.getString("col_kinds_json")),
                            node(rs.getString("pages_json")), rs.getInt("first_page"),
                            node(rs.getString("merged_json")), node(rs.getString("flags_json")),
                            List.copyOf(segs.getOrDefault(rs.getString("row_id"), List.of())));
                }).list();
        rows.put("ledger_rows", (long) lrows.size());
        List<Ledger.Unextracted> un = jdbc.sql("SELECT attachment, page, kind, ref, text FROM ledger_unextracted")
                .query((rs, i) -> new Ledger.Unextracted(rs.getString(1), rs.getInt(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)))
                .list();
        rows.put("ledger_unextracted", (long) un.size());
        return new Ledger(versions, lrows, un);
    }

    private Catalog loadCatalog(Map<String, Long> rows) {
        List<Catalog.Source> sources = jdbc.sql("SELECT name, path, version, sha256 FROM catalog_sources ORDER BY name")
                .query((rs, i) -> new Catalog.Source(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4)))
                .list();
        rows.put("catalog_sources", (long) sources.size());
        Map<String, List<Catalog.Option>> opts = new HashMap<>();
        long[] nOpt = {0};
        jdbc.sql("SELECT fact_id, ord, label, kind FROM fact_options ORDER BY fact_id, ord").query(rs -> {
            nOpt[0]++;
            opts.computeIfAbsent(rs.getString("fact_id"), k -> new ArrayList<>())
                    .add(new Catalog.Option(rs.getInt("ord"), rs.getString("label"), rs.getString("kind")));
        });
        rows.put("fact_options", nOpt[0]);
        List<Catalog.Fact> facts = jdbc.sql("""
                SELECT fact_id, ord, slot, is_common, fact_usage, question, allowed_raw, is_multi, ledger_lookup,
                       ledger_attachments, on_unknown, basis_keys, interp FROM fact_catalog ORDER BY ord""")
                .query((rs, i) -> new Catalog.Fact(rs.getString("fact_id"), rs.getInt("ord"), rs.getString("slot"),
                        rs.getBoolean("is_common"), rs.getString("fact_usage"), rs.getString("question"),
                        rs.getString("allowed_raw"), rs.getBoolean("is_multi"), rs.getString("ledger_lookup"),
                        Packages.strings(node(rs.getString("ledger_attachments"))), rs.getString("on_unknown"),
                        rs.getString("basis_keys"), rs.getString("interp"),
                        List.copyOf(opts.getOrDefault(rs.getString("fact_id"), List.of()))))
                .list();
        rows.put("fact_catalog", (long) facts.size());
        Map<String, List<String>> svals = new HashMap<>();
        long[] nSv = {0};
        jdbc.sql("SELECT slot_key, value FROM slot_options ORDER BY slot_key, ord").query(rs -> {
            nSv[0]++;
            svals.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
        });
        rows.put("slot_options", nSv[0]);
        List<Catalog.Slot> slots = jdbc.sql("""
                SELECT slot_key, label, value_type, ask_condition, rule_set_version, sort_order FROM slot_catalog
                ORDER BY sort_order""")
                .query((rs, i) -> new Catalog.Slot(rs.getString("slot_key"), rs.getString("label"),
                        rs.getString("value_type"), node(rs.getString("ask_condition")),
                        rs.getString("rule_set_version"), rs.getInt("sort_order"),
                        List.copyOf(svals.getOrDefault(rs.getString("slot_key"), List.of()))))
                .list();
        rows.put("slot_catalog", (long) slots.size());
        return new Catalog(facts, slots, sources);
    }

    private RuleSet loadRuleSet(Map<String, Long> rows) {
        List<RuleSet.RiskRule> rules = new ArrayList<>();
        Map<String, String> ruleVersion = new HashMap<>();
        jdbc.sql("""
                SELECT version, rule_id, name, axis, condition_json, grade, issue_name, legal_refs, description, active
                FROM risk_rules ORDER BY version, rule_id""").query(rs -> {
            rules.add(new RuleSet.RiskRule(rs.getString("rule_id"), rs.getString("name"), rs.getString("axis"),
                    node(rs.getString("condition_json")), rs.getString("grade"), rs.getString("issue_name"),
                    Packages.strings(node(rs.getString("legal_refs"))), rs.getString("description"),
                    rs.getBoolean("active")));
            ruleVersion.put(rs.getString("rule_id"), rs.getString("version"));
        });
        rows.put("risk_rules", (long) rules.size());
        List<RuleSet> sets = jdbc.sql("SELECT version, status, change_reason, exit_policy, sha256 FROM rule_sets")
                .query((rs, i) -> {
                    Map<String, String> exit = new LinkedHashMap<>();
                    for (var e : node(rs.getString("exit_policy")).properties()) {
                        exit.put(e.getKey(), e.getValue().asString());
                    }
                    String v = rs.getString("version");
                    return new RuleSet(v, rs.getString("status"), rs.getString("change_reason"), Map.copyOf(exit),
                            rs.getString("sha256"),
                            rules.stream().filter(r -> v.equals(ruleVersion.get(r.ruleId()))).toList());
                }).list();
        rows.put("rule_sets", (long) sets.size());
        if (sets.size() > 1) {
            throw new KnowledgeLoadException(List.of("rule_sets에 판이 " + sets.size() + "개(하나여야 함)"));
        }
        return sets.isEmpty() ? new RuleSet(null, null, null, Map.of(), null, List.of()) : sets.get(0);
    }

    private SlotRules loadSlotRules(Map<String, Long> rows, Catalog catalog) {
        Set<String> versions = new LinkedHashSet<>();
        List<SlotRules.Rule> rules = jdbc.sql("""
                SELECT version, slot_key, ord, when_json, effect, values_json, flag, basis_keys, note FROM slot_rules
                ORDER BY slot_key, ord""").query((rs, i) -> {
            versions.add(rs.getString("version"));
            return new SlotRules.Rule(rs.getString("slot_key"), rs.getInt("ord"), node(rs.getString("when_json")),
                    rs.getString("effect"), Packages.strings(node(rs.getString("values_json"))), rs.getString("flag"),
                    Packages.strings(node(rs.getString("basis_keys"))), rs.getString("note"));
        }).list();
        rows.put("slot_rules", (long) rules.size());
        if (versions.size() > 1) {
            throw new KnowledgeLoadException(List.of("slot_rules에 판이 여럿: " + versions));
        }
        return new SlotRules(versions.isEmpty() ? null : versions.iterator().next(), rules,
                catalog.slots().stream().map(Catalog.Slot::slotKey).toList());
    }

    private JsonNode node(String s) {
        return s == null ? json.missingNode() : json.readTree(s);
    }

    private static Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static LocalDate date(ResultSet rs, String col) throws SQLException {
        return rs.getObject(col, LocalDate.class);
    }

    static long usedHeapAfterGc() {
        System.gc();
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
}
