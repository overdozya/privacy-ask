package io.github.overdozya.privacyask.record;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.overdozya.privacyask.knowledge.Knowledge;
import io.github.overdozya.privacyask.knowledge.LoadedDb;

/** 테스트 MySQL(LoadedDb)에 붙은 CaseStore·CaseReader·CaseLock. 스프링 없이 만든다. */
final class RecordFixture {
    /** 실행 중 PromptCatalog 대신: 이 ID만 "목록에 없음". 예시 사안 기록(A0_intake 등)은 받는다. */
    static final String UNKNOWN_PROMPT = "Z9_unknown";

    final JdbcClient jdbc = LoadedDb.jdbc();
    final TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(LoadedDb.dataSource()));
    final CaseStore store = new CaseStore(jdbc, tx, Knowledge.of(LoadedDb.snapshot()), LoadedDb.JSON,
            (id, v) -> !UNKNOWN_PROMPT.equals(id));
    final CaseReader reader = new CaseReader(jdbc, LoadedDb.JSON);
    final CaseLock lock = new CaseLock(jdbc, tx);

    static final List<String> D9 = List.of("cases", "steps", "flows", "turns", "facts", "fact_values",
            "fact_ledger_rows", "case_judgments", "case_judgment_slots", "case_judgment_slot_values",
            "case_judgment_issues", "case_judgment_citations");

    /** 사안 하나의 D9 행 수(테이블 순서 = D9). */
    Map<String, Long> counts(String caseId) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (String t : List.of("cases", "steps", "flows", "turns", "facts", "case_judgments", "case_judgment_slots")) {
            m.put(t, jdbc.sql("SELECT COUNT(*) FROM " + t + " WHERE case_id=?").param(caseId).query(Long.class).single());
        }
        m.put("fact_values", one("SELECT COUNT(*) FROM fact_values v JOIN facts f USING (fact_row_id) WHERE f.case_id=?", caseId));
        m.put("fact_ledger_rows", one("SELECT COUNT(*) FROM fact_ledger_rows l JOIN facts f USING (fact_row_id) WHERE f.case_id=?", caseId));
        for (String t : List.of("case_judgment_slot_values", "case_judgment_issues", "case_judgment_citations")) {
            m.put(t, one("SELECT COUNT(*) FROM " + t + " x JOIN case_judgments j USING (judgment_id) WHERE j.case_id=?", caseId));
        }
        Map<String, Long> ordered = new LinkedHashMap<>();
        D9.forEach(t -> ordered.put(t, m.get(t)));
        return ordered;
    }

    long one(String sql, Object... args) {
        return jdbc.sql(sql).params(args).query(Long.class).single();
    }
}
