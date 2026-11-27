package io.github.overdozya.privacyask.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import io.github.overdozya.privacyask.llm.FakeProvider;
import io.github.overdozya.privacyask.llm.LlmProvider;
import io.github.overdozya.privacyask.llm.LlmResult;
import io.github.overdozya.privacyask.llm.PingRunner;
import io.github.overdozya.privacyask.llm.PromptCatalog;
import io.github.overdozya.privacyask.llm.LlmGateway;
import io.github.overdozya.privacyask.common.AppClock;

/** 빈 MySQL 8.4에 V1~V7이 들어가고 칸 제약이 실제로 막는지(06 2.2·2.3, 7절 S2 ①②). */
@SpringBootTest(properties = { "app.llm.provider=fake", "app.knowledge.row-check=false" })
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class SchemaMigrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = MySqlContainers.mysql84();

    @Autowired JdbcClient jdbc;
    @Autowired LlmProvider provider;
    @Autowired LlmGateway gateway;
    @Autowired AppClock clock;

    private static final LocalDateTime T = LocalDateTime.of(2026, 11, 27, 0, 0);

    @Test
    void allSevenMigrationsAppliedAndTablesExist() {
        List<String> versions = jdbc.sql("SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank")
                .query(String.class).list();
        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7");
        Set<String> tables = new TreeSet<>(jdbc.sql("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history'
                """).query(String.class).list());
        assertThat(tables).containsExactlyInAnyOrder(
                "kb_documents", "guide_sections", "kb_chunks", "kb_annexes", "kb_links", "build_info",
                "issue_packages", "package_items", "package_item_units",
                "ledger_versions", "ledger_rows", "ledger_segments", "ledger_unextracted",
                "catalog_sources", "fact_catalog", "fact_options", "slot_catalog", "slot_options",
                "rule_sets", "risk_rules", "slot_rules",
                "cases", "steps", "flows", "turns", "facts", "fact_values", "fact_ledger_rows",
                "case_judgments", "case_judgment_slots", "case_judgment_slot_values", "case_judgment_issues",
                "case_judgment_citations", "llm_call_logs", "turn_logs");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM information_schema.views WHERE table_schema = DATABASE() AND table_name = 'facts_current'")
                .query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void keyColumnsAreBinaryCollation() {
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT table_name AS t, column_name AS c, collation_name AS k FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND column_name IN ('unit_key','annex_key','section_id','parent_id','target_key','chunk_key','row_id',
                                      'fact_id','slot_key','package_id')
                  AND data_type IN ('varchar','char')
                """).query().listOfRows();
        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(r -> assertThat(r.get("k")).as(r.get("t") + "." + r.get("c")).isEqualTo("utf8mb4_bin"));
        assertThat(jdbc.sql("SELECT collation_name FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'fact_values' AND column_name = 'label'")
                .query(String.class).single()).isEqualTo("utf8mb4_bin");
    }

    private void seedCase() {
        jdbc.sql("""
                INSERT INTO fact_catalog (fact_id, ord, slot, is_common, fact_usage, question, allowed_raw, is_multi,
                  ledger_lookup, ledger_attachments, on_unknown, basis_keys, interp)
                VALUES ('F-T1', 1, '공통', 1, '시험', '시험 질문', '예 / 아니오', 0, '—', '[]', '—', '—', '—')
                """).update();
        jdbc.sql("INSERT INTO cases (case_id, requester_id, content, state, rule_set_version, package_version, created_at, updated_at) VALUES ('c1', 'u1', '지어낸 문의', 'ASKING', 'v1.0', 'v2', :t, :t)")
                .param("t", T).update();
        jdbc.sql("INSERT INTO steps (case_id, seq, step, actor, name, input_json, output_json, status, at) VALUES ('c1', 1, 'A1', 'CODE', '시험', '{}', '{}', 'OK', :t)")
                .param("t", T).update();
    }

    private void insertFact(Integer flowNo, int rev) {
        jdbc.sql("INSERT INTO facts (case_id, flow_no, fact_id, rev, status, source_kind, source_step, recorded_at) VALUES ('c1', :f, 'F-T1', :r, 'CONFIRMED', 'INQUIRY', 1, :t)")
                .param("f", flowNo).param("r", rev).param("t", T).update();
    }

    @Test
    void commonFactRevisionIsUniqueViaFlowKey() {
        seedCase();
        insertFact(null, 1);
        assertThatThrownBy(() -> insertFact(null, 1)).isInstanceOf(DuplicateKeyException.class);
        insertFact(null, 2);
        assertThat(jdbc.sql("SELECT rev FROM facts_current WHERE case_id = 'c1' AND fact_id = 'F-T1'").query(Integer.class).list())
                .containsExactly(2);
    }

    @Test
    void factOptionLabelsCompareByBytes() {
        seedCase();
        for (String label : List.of("A", "a", "é", "e")) {
            jdbc.sql("INSERT INTO fact_options (fact_id, ord, label, kind) VALUES ('F-T1', 1, :l, 'CHOICE')").param("l", label).update();
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM fact_options WHERE fact_id = 'F-T1'").query(Long.class).single()).isEqualTo(4L);
        // 허용 값 밖 라벨은 fact_values FK가 막는다(C04)
        insertFact(null, 1);
        Long row = jdbc.sql("SELECT fact_row_id FROM facts WHERE case_id = 'c1'").query(Long.class).single();
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO fact_values (fact_row_id, fact_id, ord, label) VALUES (:r, 'F-T1', 1, 'Z')")
                .param("r", row).update()).isInstanceOf(DataAccessException.class);
    }

    @Test
    void checksAreEnforced() {
        seedCase();
        // LLM 단계는 프롬프트 ID·판·모델·온도가 있어야 한다(C17 칸 제약)
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO steps (case_id, seq, step, actor, name, input_json, output_json, status, at) VALUES ('c1', 2, 'A0', 'LLM', 'x', '{}', '{}', 'OK', :t)")
                .param("t", T).update()).isInstanceOf(DataAccessException.class).hasMessageContaining("ck_steps_llm");
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO cases (case_id, requester_id, content, state, rule_set_version, package_version, created_at, updated_at) VALUES ('c2', 'u1', 'x', 'DONE', 'v1.0', 'v2', :t, :t)")
                .param("t", T).update()).isInstanceOf(DataAccessException.class).hasMessageContaining("ck_cases_state");
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO llm_call_logs (prompt_id, prompt_version, provider, model, attempt_no, outcome, input_sha256, at) VALUES ('S0_ping', '1-abc', 'fake', 'm', 1, 'OK', REPEAT('0', 64), :t)")
                .param("t", T).update()).isInstanceOf(DataAccessException.class).hasMessageContaining("ck_llm_call_logs_version");
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO kb_links (link_id, link_type, src_key, dst_key, resolution, origin) VALUES (1, '아무거나', 'a', 'b', '정확', '본문')")
                .update()).isInstanceOf(DataAccessException.class).hasMessageContaining("ck_kb_links_type");
        // 슬롯 변환 SKIP은 값이 없어야 한다(S3 slot_rules의 세 번째 효과)
        jdbc.sql("INSERT INTO slot_catalog VALUES ('night_transmission', '야간 전송', 'SINGLE', NULL, 'v1.0', 80)").update();
        jdbc.sql("INSERT INTO slot_rules VALUES ('v1', 'night_transmission', 1, '{\"fact\":\"F-T1\",\"op\":\"eq\",\"value\":\"x\"}', 'SKIP', '[]', NULL, '[]', NULL)").update();
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO slot_rules VALUES ('v1', 'night_transmission', 2, '{}', 'SKIP', '[\"아니오\"]', NULL, '[]', NULL)")
                .update()).isInstanceOf(DataAccessException.class).hasMessageContaining("ck_slot_rules_skip");
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO slot_rules VALUES ('v1', 'night_transmission', 3, '{}', 'DROP', '[]', NULL, '[]', NULL)")
                .update()).isInstanceOf(DataAccessException.class).hasMessageContaining("ck_slot_rules_effect");
    }

    @Test
    void ledgerFieldsJsonKeepsTextAndMustBeValid() {
        jdbc.sql("INSERT INTO ledger_versions VALUES ('20261028', '2026-10-28', '첨부1', 't', 'f', REPEAT('0', 64), 1, 'x')").update();
        String fields = "[[\"구분\",   \"이동전화\"],\n [\"항목\", \"성명\"]]";
        jdbc.sql("INSERT INTO ledger_rows VALUES ('첨부1-0001', '20261028', '첨부1', 1, 1, 't', 's', 's', :f, '[]', '[1]', 1, '[]', '[]')")
                .param("f", fields).update();
        assertThat(jdbc.sql("SELECT fields_json FROM ledger_rows WHERE row_id = '첨부1-0001'").query(String.class).single())
                .isEqualTo(fields);
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO ledger_rows VALUES ('첨부1-0002', '20261028', '첨부1', 2, 1, 't', 's', 's', 'not json', '[]', '[1]', 1, '[]', '[]')")
                .update()).isInstanceOf(DataAccessException.class).hasMessageContaining("ck_ledger_rows_fields");
    }

    @Test
    void sessionClockIsUtc() {
        assertThat(jdbc.sql("SELECT TIMESTAMPDIFF(SECOND, NOW(), UTC_TIMESTAMP())").query(Long.class).single()).isZero();
    }

    @Test
    void pingThroughGatewayWritesCallLog() {
        FakeProvider fake = (FakeProvider) provider;
        fake.then(req -> {
            String nonce = req.user().replaceAll("(?s).*\"nonce\"\\s*:\\s*\"([0-9a-f]+)\".*", "$1");
            return FakeProvider.ok("{\"status\":\"ok\",\"echo\":\"" + nonce + "\"}");
        });
        LlmResult<PingRunner.Pong> r = new PingRunner(gateway, clock).ping();
        assertThat(r.status()).isEqualTo(LlmResult.Status.OK);
        Map<String, Object> row = jdbc.sql("SELECT * FROM llm_call_logs WHERE call_id = :id").param("id", r.callIds().get(0))
                .query().singleRow();
        assertThat(row.get("prompt_id")).isEqualTo("S0_ping");
        assertThat((String) row.get("prompt_version")).matches(PromptCatalog.VERSION_TAG);
        assertThat(row.get("provider")).isEqualTo("fake");
        assertThat(row.get("model")).isEqualTo("gemini-3.6-flash");
        assertThat(row.get("temperature").toString()).isEqualTo("0.00");
        assertThat(row.get("outcome")).isEqualTo("OK");
        assertThat(row.get("finish_reason")).isEqualTo("STOP");
        assertThat(row.get("case_id")).isNull();
        assertThat(row.get("attempt_no")).isEqualTo(1);
        assertThat(row.get("prompt_tokens")).isEqualTo(10);
    }
}
