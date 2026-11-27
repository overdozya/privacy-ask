package io.github.overdozya.privacyask.verify;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 판정 테이블 읽기(API 2·3·4의 최종 결과): case_judgments 마지막 행 + 슬롯·값·쟁점 + A8 단계가 기댄 단계 번호.
 * 슬롯 근거 사실은 fact_row_id → 사실 ID로 바꿔 돌려준다.
 */
public final class JudgmentReader {

    public record SlotRow(int flow, String slot, String status, List<String> values, List<String> basisFacts,
                          List<String> unknownFacts) {}

    public record IssueRow(int flow, String issue, String packageId, String grade, List<String> ruleIds,
                           List<String> conditions, List<String> interp, boolean conclusive, String opinion,
                           String actions) {}

    /** fromSteps = A8 단계 input의 from_steps(이름 → seq). */
    public record Stored(long judgmentId, int stepSeq, String exitType, String caseGrade, boolean conclusive,
                         JsonNode exitLines, JsonNode flags, JsonNode bundle, List<SlotRow> slots, List<IssueRow> issues,
                         Map<String, Integer> fromSteps) {}

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public JudgmentReader(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<Stored> latest(String caseId) {
        Optional<Map<String, Object>> row = jdbc.sql("""
                SELECT j.judgment_id, j.step_seq, j.exit_type, j.case_grade, j.conclusive, j.exit_reasons, j.flags,
                       j.review_bundle, s.input_json
                FROM case_judgments j JOIN steps s ON s.case_id=j.case_id AND s.seq=j.step_seq
                WHERE j.case_id=? ORDER BY j.judgment_id DESC LIMIT 1""").param(caseId).query().listOfRows().stream()
                .findFirst();
        if (row.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> r = row.get();
        long id = ((Number) r.get("judgment_id")).longValue();
        Map<Long, String> factIds = new HashMap<>();
        jdbc.sql("SELECT fact_row_id, fact_id FROM facts WHERE case_id=?").param(caseId).query()
                .listOfRows().forEach(x -> factIds.put(((Number) x.get("fact_row_id")).longValue(), (String) x.get("fact_id")));
        Map<String, List<String>> values = new HashMap<>();
        jdbc.sql("SELECT flow_no, slot_key, value FROM case_judgment_slot_values WHERE judgment_id=? ORDER BY flow_no, slot_key, ord")
                .param(id).query().listOfRows().forEach(x -> values.computeIfAbsent(x.get("flow_no") + "|" + x.get("slot_key"),
                        k -> new ArrayList<>()).add((String) x.get("value")));
        List<SlotRow> slots = new ArrayList<>();
        for (Map<String, Object> x : jdbc.sql("""
                SELECT s.flow_no, s.slot_key, s.status, s.basis_facts, s.unknown_facts FROM case_judgment_slots s
                JOIN slot_catalog c ON c.slot_key=s.slot_key WHERE s.judgment_id=? ORDER BY s.flow_no, c.sort_order""")
                .param(id).query().listOfRows()) {
            int flow = ((Number) x.get("flow_no")).intValue();
            String slot = (String) x.get("slot_key");
            List<String> basis = new ArrayList<>();
            for (JsonNode b : json.readTree(String.valueOf(x.get("basis_facts")))) {
                basis.add(factIds.getOrDefault(b.asLong(), String.valueOf(b.asLong())));
            }
            slots.add(new SlotRow(flow, slot, (String) x.get("status"), values.getOrDefault(flow + "|" + slot, List.of()),
                    basis, strings(json.readTree(String.valueOf(x.get("unknown_facts"))))));
        }
        List<IssueRow> issues = new ArrayList<>();
        for (Map<String, Object> x : jdbc.sql("""
                SELECT flow_no, issue_name, package_id, grade, rule_ids, conditions, interp_flags, conclusive, opinion, actions
                FROM case_judgment_issues WHERE judgment_id=? ORDER BY issue_row_id""").param(id).query().listOfRows()) {
            issues.add(new IssueRow(((Number) x.get("flow_no")).intValue(), (String) x.get("issue_name"),
                    (String) x.get("package_id"), (String) x.get("grade"), strings(read(x.get("rule_ids"))),
                    strings(read(x.get("conditions"))), strings(read(x.get("interp_flags"))), truthy(x.get("conclusive")),
                    (String) x.get("opinion"), (String) x.get("actions")));
        }
        Map<String, Integer> from = new LinkedHashMap<>();
        JsonNode in = read(r.get("input_json"));
        if (in != null && in.has("from_steps")) {
            in.get("from_steps").properties().forEach(e -> {
                if (e.getValue().isNumber()) {
                    from.put(e.getKey(), e.getValue().asInt());
                }
            });
        }
        return Optional.of(new Stored(id, ((Number) r.get("step_seq")).intValue(), (String) r.get("exit_type"),
                (String) r.get("case_grade"), truthy(r.get("conclusive")), read(r.get("exit_reasons")),
                read(r.get("flags")), read(r.get("review_bundle")), slots, issues, from));
    }

    JsonNode read(Object o) {
        return o == null ? null : json.readTree(String.valueOf(o));
    }

    static boolean truthy(Object o) {
        return o instanceof Boolean b ? b : o instanceof Number n && n.intValue() != 0;
    }

    static List<String> strings(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n != null && n.isArray()) {
            n.forEach(x -> out.add(x.asString()));
        }
        return out;
    }
}
