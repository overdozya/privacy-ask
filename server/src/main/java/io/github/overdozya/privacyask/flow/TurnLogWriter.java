package io.github.overdozya.privacyask.flow;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;

import tools.jackson.databind.json.JsonMapper;

/**
 * turn_logs(V7) 한 행 = 요청 하나(06 6.4). 측정이 끝난 뒤 쓰므로 이 INSERT는 db_statements에 들어가지 않는다.
 * action_path = {"path": NextAction 차례, "steps": 이번에 쓴 단계 번호, "a2": A2 계산(본 후보·판정 횟수·µs)}.
 * turn_no = 이번에 처리한 마지막 사용자 입력 턴(0 = 첫 문의).
 */
public class TurnLogWriter {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public TurnLogWriter(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void write(String caseId, Integer turnNo, String kind, TurnResult.Timing t, TurnMeter m, List<Integer> steps,
                      String resultCode, LocalDateTime at) {
        Map<String, Object> path = new LinkedHashMap<>();
        path.put("path", m.path());
        path.put("steps", steps);
        if (!m.a2().isEmpty()) {
            path.put("a2", new java.util.TreeMap<>(m.a2()));
        }
        jdbc.sql("""
                INSERT INTO turn_logs (case_id, turn_no, request_kind, total_ms, llm_ms, llm_calls, llm_depth, db_statements,
                                       action_path, result_code, at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(caseId, turnNo, kind, t.turnMs(), t.llmMs(), t.llmCalls(), t.llmDepth(), t.dbStatements(),
                        json.writeValueAsString(path), cut(resultCode, 30), at)
                .update();
    }

    private static String cut(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
