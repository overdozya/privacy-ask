package io.github.overdozya.privacyask.issues;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.overdozya.privacyask.knowledge.LoadedDb;
import io.github.overdozya.privacyask.rules.Condition;
import io.github.overdozya.privacyask.rules.FactState;
import tools.jackson.databind.JsonNode;

/**
 * 예시 사안 case.db(build/가 지음, 재현표와 같은 DB)를 sqlite3 명령으로 읽는다. 지금 사실 판·판정 슬롯·판정 쟁점.
 * slot_rules.py example_flows와 같은 질의. 파일이나 sqlite3가 없으면 available()이 false.
 */
public final class ExampleCaseDb {
    public static final Path CASE_DB = LoadedDb.DATA.resolve("out").resolve("case").resolve("case.db");

    private ExampleCaseDb() {}

    public static boolean available() {
        if (!Files.isRegularFile(CASE_DB)) {
            return false;
        }
        try {
            return new ProcessBuilder("sqlite3", "-version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static JsonNode query(String sql) {
        try {
            Process p = new ProcessBuilder("sqlite3", "-readonly", "-json", CASE_DB.toString(), sql).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (p.waitFor() != 0) {
                throw new IllegalStateException(sql + " → " + err);
            }
            return LoadedDb.JSON.readTree(out.isEmpty() ? "[]" : out);
        } catch (java.io.IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String caseId() {
        return query("SELECT case_id FROM cases").get(0).get("case_id").asString();
    }

    public static List<Integer> flowNos() {
        List<Integer> out = new ArrayList<>();
        query("SELECT flow_no FROM flows ORDER BY flow_no").forEach(r -> out.add(r.get("flow_no").asInt()));
        return out;
    }

    /** 흐름 하나의 지금 사실(공통 포함). 값은 값이 있는 상태·UNKNOWN일 때만(slot_rules.py와 같음). */
    public static Map<String, FactState> facts(int flowNo) {
        JsonNode rows = query("SELECT f.fact_id, f.flow_no, f.status, f.source_kind, "
                + "(SELECT json_group_array(label) FROM (SELECT label FROM fact_values v WHERE v.fact_row_id=f.fact_row_id ORDER BY ord)) AS vals, "
                + "(SELECT json_group_array(row_id) FROM (SELECT row_id FROM fact_ledger_rows r WHERE r.fact_row_id=f.fact_row_id ORDER BY ord)) AS rws "
                + "FROM facts_current f WHERE f.flow_no=" + flowNo + " OR f.flow_no IS NULL ORDER BY f.fact_row_id");
        Map<String, FactState> m = new LinkedHashMap<>();
        for (JsonNode r : rows) {
            String st = r.get("status").asString();
            List<String> vals = Condition.strings(LoadedDb.JSON.readTree(r.get("vals").asString()));
            if (!(FactState.KNOWN.contains(st) || FactState.UNKNOWN.equals(st))) {
                vals = List.of();
            }
            m.put(r.get("fact_id").asString(), new FactState(st, vals,
                    Condition.strings(LoadedDb.JSON.readTree(r.get("rws").asString())), r.get("source_kind").asString()));
        }
        return m;
    }

    /** 마지막 판정의 슬롯: 슬롯 키 → {status, values, basis_facts(사실 ID), unknown_facts}. */
    public static Map<String, Map<String, Object>> judgedSlots(int flowNo) {
        JsonNode rows = query("SELECT s.slot_key, s.status, s.unknown_facts, "
                + "(SELECT json_group_array(value) FROM (SELECT value FROM case_judgment_slot_values v WHERE v.judgment_id=s.judgment_id "
                + "AND v.flow_no=s.flow_no AND v.slot_key=s.slot_key ORDER BY ord)) AS vals, "
                + "(SELECT json_group_array(f.fact_id) FROM json_each(s.basis_facts) j JOIN facts f ON f.fact_row_id=j.value) AS basis "
                + "FROM case_judgment_slots s WHERE s.judgment_id=(SELECT MAX(judgment_id) FROM case_judgments) AND s.flow_no=" + flowNo);
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (JsonNode r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", r.get("status").asString());
            m.put("values", Condition.strings(LoadedDb.JSON.readTree(r.get("vals").asString())));
            m.put("basis_facts", Condition.strings(LoadedDb.JSON.readTree(r.get("basis").asString())));
            m.put("unknown_facts", Condition.strings(LoadedDb.JSON.readTree(r.get("unknown_facts").asString())));
            out.put(r.get("slot_key").asString(), m);
        }
        return out;
    }

    /** 마지막 판정의 쟁점: [이름, 꾸러미, 등급, 규칙, 조건, 해석 쟁점] 목록(issue_row_id 순). */
    public static List<List<Object>> judgedIssues(int flowNo) {
        JsonNode rows = query("SELECT issue_name, package_id, grade, rule_ids, conditions, interp_flags FROM case_judgment_issues "
                + "WHERE judgment_id=(SELECT MAX(judgment_id) FROM case_judgments) AND flow_no=" + flowNo + " ORDER BY issue_row_id");
        List<List<Object>> out = new ArrayList<>();
        for (JsonNode r : rows) {
            out.add(List.of(r.get("issue_name").asString(), r.get("package_id").asString(), r.get("grade").asString(),
                    Condition.strings(LoadedDb.JSON.readTree(r.get("rule_ids").asString())),
                    Condition.strings(LoadedDb.JSON.readTree(r.get("conditions").asString())),
                    Condition.strings(LoadedDb.JSON.readTree(r.get("interp_flags").asString()))));
        }
        return out;
    }

    public static JsonNode judgment() {
        return query("SELECT case_grade, exit_type, exit_reasons FROM case_judgments ORDER BY judgment_id DESC LIMIT 1").get(0);
    }
}
