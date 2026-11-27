package io.github.overdozya.privacyask.record;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 사안 상태를 한 번에 읽는다(06 3.4·6.3): 사안·흐름·턴·사실(값·원장 행 포함)·단계 요약 = 5문장. 단계 JSON 칸은 읽지 않는다.
 * 사안 상태는 캐시하지 않는다(6.2: 정확성이 먼저).
 */
public class CaseReader {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public CaseReader(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<CaseState> load(String caseId) {
        Optional<CaseState.CaseRow> c = jdbc.sql("SELECT * FROM cases WHERE case_id=?").param(caseId)
                .query((rs, i) -> new CaseState.CaseRow(rs.getString("case_id"), rs.getString("requester_id"),
                        rs.getString("content"), rs.getString("service_name"), rs.getString("intake_type"),
                        rs.getString("out_of_scope_kind"), rs.getString("state"), rs.getString("rule_set_version"),
                        rs.getString("package_version"), rs.getString("ledger_version"),
                        rs.getObject("reference_date", LocalDate.class), rs.getString("reference_date_basis"),
                        ts(rs, "created_at"), ts(rs, "updated_at"), ts(rs, "closed_at")))
                .optional();
        if (c.isEmpty()) {
            return Optional.empty();
        }
        List<CaseState.Flow> flows = jdbc.sql("SELECT * FROM flows WHERE case_id=? ORDER BY flow_no").param(caseId)
                .query((rs, i) -> new CaseState.Flow(rs.getInt("flow_no"), rs.getString("label"), rs.getString("origin"),
                        rs.getString("destination"), rs.getString("service_name"), rs.getInt("created_step")))
                .list();
        List<CaseState.Turn> turns = jdbc.sql("SELECT * FROM turns WHERE case_id=? ORDER BY turn_no").param(caseId)
                .query((rs, i) -> new CaseState.Turn(rs.getInt("turn_no"), rs.getString("role"), rs.getString("kind"),
                        intOrNull(rs, "flow_no"), rs.getString("fact_id"), intOrNull(rs, "ask_no"),
                        intOrNull(rs, "reply_to"), rs.getString("text"), strings(rs.getString("choices_json")),
                        rs.getInt("step_seq"), ts(rs, "at")))
                .list();
        Map<Long, CaseState.Fact> facts = new LinkedHashMap<>();
        Map<Long, List<CaseInput.Value>> values = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT f.*, v.label, v.detail,
                       (SELECT GROUP_CONCAT(l.row_id ORDER BY l.ord SEPARATOR ',') FROM fact_ledger_rows l
                        WHERE l.fact_row_id = f.fact_row_id) AS ledger_rows
                FROM facts f LEFT JOIN fact_values v ON v.fact_row_id = f.fact_row_id
                WHERE f.case_id=? ORDER BY f.fact_row_id, v.ord""").param(caseId).query(rs -> {
            long id = rs.getLong("fact_row_id");
            List<CaseInput.Value> vs = values.computeIfAbsent(id, x -> new ArrayList<>());
            if (rs.getString("label") != null) {
                vs.add(new CaseInput.Value(rs.getString("label"), rs.getString("detail")));
            }
            if (!facts.containsKey(id)) {
                String lr = rs.getString("ledger_rows");
                facts.put(id, new CaseState.Fact(id, intOrNull(rs, "flow_no"), rs.getString("fact_id"), rs.getInt("rev"),
                        rs.getString("status"), rs.getString("source_kind"), intOrNull(rs, "source_turn"),
                        rs.getInt("source_step"), rs.getString("basis_quote"), rs.getString("ledger_version"),
                        rs.getString("note"), ts(rs, "recorded_at"), vs,
                        lr == null ? List.of() : List.of(lr.split(","))));
            }
        });
        List<CaseState.Fact> factList = facts.values().stream().map(f -> new CaseState.Fact(f.factRowId(), f.flowNo(),
                f.factId(), f.rev(), f.status(), f.sourceKind(), f.sourceTurn(), f.sourceStep(), f.basisQuote(),
                f.ledgerVersion(), f.note(), f.recordedAt(), List.copyOf(f.values()), f.ledgerRows())).toList();
        List<CaseState.StepSummary> steps = jdbc.sql("""
                SELECT seq, step, flow_no, actor, name, prompt_id, prompt_version, model_name, temperature, status,
                       attempts, at FROM steps WHERE case_id=? ORDER BY seq""").param(caseId)
                .query((rs, i) -> new CaseState.StepSummary(rs.getInt("seq"), rs.getString("step"),
                        intOrNull(rs, "flow_no"), rs.getString("actor"), rs.getString("name"),
                        rs.getString("prompt_id"), rs.getString("prompt_version"), rs.getString("model_name"),
                        rs.getBigDecimal("temperature"), rs.getString("status"), rs.getInt("attempts"), ts(rs, "at")))
                .list();
        return Optional.of(new CaseState(c.get(), List.copyOf(flows), List.copyOf(turns), factList, List.copyOf(steps)));
    }

    /** 단계 기록 전부(JSON 포함). GET /api/cases/{id}/trace(5절 API 5)용. */
    public record StepDetail(int seq, String step, Integer flowNo, String actor, String name, String promptId,
                             String promptVersion, String modelName, BigDecimal temperature, String codeVersion,
                             JsonNode input, JsonNode output, String status, int attempts, LocalDateTime at) {}

    public List<StepDetail> trace(String caseId) {
        return jdbc.sql("SELECT * FROM steps WHERE case_id=? ORDER BY seq").param(caseId)
                .query((rs, i) -> new StepDetail(rs.getInt("seq"), rs.getString("step"), intOrNull(rs, "flow_no"),
                        rs.getString("actor"), rs.getString("name"), rs.getString("prompt_id"),
                        rs.getString("prompt_version"), rs.getString("model_name"), rs.getBigDecimal("temperature"),
                        rs.getString("code_version"), json.readTree(rs.getString("input_json")),
                        json.readTree(rs.getString("output_json")), rs.getString("status"), rs.getInt("attempts"),
                        ts(rs, "at")))
                .list();
    }

    /** 본인 사안 목록(최근 순, 쪽 나눔). 5절 API 6. */
    public List<CaseState.CaseRow> listByRequester(String requesterId, int limit, int offset) {
        return jdbc.sql("SELECT case_id FROM cases WHERE requester_id=? ORDER BY created_at DESC, case_id LIMIT ? OFFSET ?")
                .params(requesterId, limit, offset).query(String.class).list().stream()
                .map(id -> load(id).orElseThrow().caseRow()).toList();
    }

    private List<String> strings(String s) {
        List<String> out = new ArrayList<>();
        if (s != null) {
            for (JsonNode x : json.readTree(s)) {
                out.add(x.asString());
            }
        }
        return List.copyOf(out);
    }

    private static Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static LocalDateTime ts(ResultSet rs, String col) throws SQLException {
        return rs.getObject(col, LocalDateTime.class);
    }
}
