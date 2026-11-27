package io.github.overdozya.privacyask.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import io.github.overdozya.privacyask.knowledge.LoadedDb;
import io.github.overdozya.privacyask.record.CaseInput.CitationEntry;
import io.github.overdozya.privacyask.record.CaseInput.NewCase;
import io.github.overdozya.privacyask.record.CaseInput.NewFact;
import io.github.overdozya.privacyask.record.CaseInput.NewJudgment;
import io.github.overdozya.privacyask.record.CaseInput.NewStep;
import io.github.overdozya.privacyask.record.CaseInput.SlotEntry;
import io.github.overdozya.privacyask.record.CaseInput.Value;

/**
 * 06 7절 S4 ④: 거부 규칙 17개 각 1건(CaseStore), DB 제약 8개는 CaseStore를 거치지 않은 우회 쓰기로도 막힘, 늦은 저장 거절.
 * 바탕은 예시 사안 하나(판정까지 넣은 것). 거부된 쓰기는 행을 남기지 않는다.
 */
class CaseStoreRejectTest {
    static RecordFixture fx;
    static ExampleCase ex;
    static String cid;
    static Map<String, Long> before;
    static final LocalDateTime T = LocalDateTime.of(2026, 11, 27, 9, 0);

    @BeforeAll
    static void base() throws Exception {
        assumeThat(ExampleCase.available()).as("예시 사안·적재 파일 또는 Docker 없음").isTrue();
        fx = new RecordFixture();
        ex = new ExampleCase();
        cid = UUID.randomUUID().toString();
        ex.load(fx.store, cid, true);
        before = fx.counts(cid);
    }

    static void rejects(String code, ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(Rejected.class, r -> assertThat(r.code()).isEqualTo(code));
        assertThat(fx.counts(cid)).as("거부 뒤 행 수 그대로").isEqualTo(before);
    }

    /** 흐름 1의 문의 출처 사실(단계 3 = A1 문의). 하나만 바꿔 쓴다. */
    static NewFact fact(String factId, Integer flow, List<Value> values, String status, String source, int step,
                        Integer turn, String quote, List<String> rows, String ledgerVersion) {
        return new NewFact(cid, factId, values, status, source, step, T, flow, turn, quote, rows, ledgerVersion, null);
    }

    static NewFact okFact() {
        return fact("F-T4", 1, List.of(Value.of("아니오")), "CONFIRMED", "INQUIRY", 3, null,
                "계약으로 맡긴 일 외에는 못 쓰게 할 예정", List.of(), null);
    }

    static NewJudgment judgment(UnaryOperator<NewJudgment> change) {
        return change.apply(ex.judgment(fx.store, cid));
    }

    static NewJudgment with(NewJudgment j, String caseGrade, Integer stepSeq, List<SlotEntry> slots,
                            List<CitationEntry> cits, String packageVersion) {
        return new NewJudgment(j.caseId(), stepSeq == null ? j.stepSeq() : stepSeq, j.judgedTurn(), j.referenceDate(),
                j.ruleSetVersion(), packageVersion == null ? j.packageVersion() : packageVersion, j.ledgerVersion(),
                j.factMapSha256(), j.modelName(), j.temperature(), j.promptVersions(),
                caseGrade == null ? j.caseGrade() : caseGrade, j.exitType(), j.exitReasons(), j.conclusive(), j.flags(),
                j.reviewBundle(), j.premiseNote(), j.createdAt(), slots == null ? j.slots() : slots, j.issues(),
                cits == null ? j.citations() : cits);
    }

    @Test
    void baseFactAndJudgmentAreAccepted() {
        // 아래 거부 시험의 바탕이 맞는 값임을 보인다(검사만, 저장 안 함)
        fx.store.checkFact(okFact());
        assertThat(fx.counts(cid).get("case_judgments")).isEqualTo(1);
    }

    // ───────── 거부 규칙 17 (CaseStore) ─────────
    @Nested
    class Rules {
        @Test
        void c01_noSource() {
            rejects("C01", () -> fx.store.addFact(fact("F-T4", 1, List.of(Value.of("아니오")), "CONFIRMED", "CHAT", 3,
                    null, "계약으로", List.of(), null)));
        }

        @Test
        void c02_noQuote() {
            rejects("C02", () -> fx.store.addFact(fact("F-T4", 1, List.of(Value.of("아니오")), "CONFIRMED", "INQUIRY", 3,
                    null, null, List.of(), null)));
        }

        @Test
        void c03_quoteNotInSource() {
            rejects("C03", () -> fx.store.addFact(fact("F-T4", 1, List.of(Value.of("아니오")), "CONFIRMED", "INQUIRY", 3,
                    null, "문의에 없는 글", List.of(), null)));
        }

        @Test
        void c04_noAnswerTurn() {
            rejects("C04", () -> fx.store.addFact(fact("F-T4", 1, List.of(Value.of("아니오")), "CONFIRMED", "ANSWER", 8,
                    99, "아니오", List.of(), null)));
        }

        @Test
        void c05_ledgerSourceForFactWithoutLedgerLookup() {
            rejects("C05", () -> fx.store.addFact(fact("F-T4", 1, List.of(Value.of("아니오")), "INFERRED", "LEDGER", 5,
                    null, null, List.of(), "20261028")));
        }

        @Test
        void c06_ledgerRowOutsideAllowedAttachments() {
            rejects("C06", () -> fx.store.addFact(fact("L1", 1, List.of(Value.of("명시 일치")), "MATCHED", "LEDGER", 5,
                    null, "위탁", List.of("첨부4-0001"), "20261028")));
        }

        @Test
        void c07_unknownFactId() {
            rejects("C07", () -> fx.store.addFact(fact("F-ZZ", 1, List.of(Value.of("예")), "CONFIRMED", "INQUIRY", 3,
                    null, "계약으로", List.of(), null)));
        }

        @Test
        void c08_valueOutsideOptions() {
            rejects("C08", () -> fx.store.addFact(fact("F-T4", 1, List.of(Value.of("어쩌면")), "CONFIRMED", "INQUIRY", 3,
                    null, "계약으로", List.of(), null)));
        }

        @Test
        void c09_unknownLabelButConfirmed() {
            rejects("C09", () -> fx.store.addFact(fact("F-T4", 1, List.of(Value.of("잘 모르겠음")), "CONFIRMED", "INQUIRY",
                    3, null, "계약으로", List.of(), null)));
        }

        @Test
        void c10_commonFactWithFlow() {
            rejects("C10", () -> fx.store.addFact(fact("F-S1", 1, List.of(new Value("서비스명(자유 입력)", "T 멤버십")),
                    "CONFIRMED", "INQUIRY", 3, null, "T 멤버십", List.of(), null)));
        }

        @Test
        void c11_unknownPackageVersion() {
            String id = UUID.randomUUID().toString();
            rejects("C11", () -> fx.store.addCase(new NewCase(id, "요청자", "문의", null, "v1.0", "v9", null, T)));
        }

        @Test
        void c11_newCaseOnPastPackageVersion() {
            // v1은 지식에 있는 지난 판: 새 사안은 지금 판(v2)으로만
            assertThat(LoadedDb.snapshot().packages().hasVersion("v1")).isTrue();
            String id = UUID.randomUUID().toString();
            assertThatThrownBy(() -> fx.store.addCase(new NewCase(id, "요청자", "문의", null, "v1.0", "v1", null, T)))
                    .isInstanceOfSatisfying(Rejected.class, r -> {
                        assertThat(r.code()).isEqualTo("C11");
                        assertThat(r.detail()).contains("지금 판 'v2'가 아님");
                    });
            assertThat(fx.one("SELECT COUNT(*) FROM cases WHERE case_id=?", id)).isZero();
        }

        @Test
        void c12_ruleSetVersionDiffers() {
            String id = UUID.randomUUID().toString();
            rejects("C12", () -> fx.store.addCase(new NewCase(id, "요청자", "문의", null, "v0.9", "v2", null, T)));
        }

        @Test
        void c13_citationHashDoesNotMatch() {
            rejects("C13", () -> fx.store.addJudgment(judgment(j -> {
                List<CitationEntry> c = new ArrayList<>(j.citations());
                CitationEntry x = c.get(0);
                c.set(0, new CitationEntry(x.issue(), x.kind(), x.key(), "0".repeat(64), x.ledgerVersion(), x.origin()));
                return with(j, null, null, null, c, null);
            })));
        }

        @Test
        void c14_slotValueOutsideOptions() {
            rejects("C14", () -> fx.store.addJudgment(judgment(j -> {
                List<SlotEntry> s = new ArrayList<>(j.slots());
                SlotEntry x = s.get(0);
                s.set(0, new SlotEntry(x.flow(), x.slot(), x.status(), List.of("없는 값"), x.basisFacts(), x.unknownFacts()));
                return with(j, null, null, s, null, null);
            })));
        }

        @Test
        void c15_caseGradeNotTopIssueGrade() {
            rejects("C15", () -> fx.store.addJudgment(judgment(j -> with(j, "L", null, null, null, null))));
        }

        @Test
        void c16_judgmentStepIsNotA8() {
            rejects("C16", () -> fx.store.addJudgment(judgment(j -> with(j, null, 22, null, null, null))));
        }

        @Test
        void c17_llmStepWithBadPromptVersion() {
            rejects("C17", () -> fx.store.addStep(NewStep.llm(cid, "A1", "사실 추출(답)", Map.of(), Map.of(), T, null,
                    "A1_facts", "1", "gemini-3.6-flash", BigDecimal.ZERO, "x", "OK", 1)));
            // 06 2.6 더함: 실행 중 PromptCatalog에 없는 프롬프트
            rejects("C17", () -> fx.store.addStep(NewStep.llm(cid, "A1", "사실 추출(답)", Map.of(), Map.of(), T, null,
                    RecordFixture.UNKNOWN_PROMPT, "1@0123abcd", "gemini-3.6-flash", BigDecimal.ZERO, "x", "OK", 1)));
        }
    }

    // ───────── S9: OPEN에서 온 UNKNOWN 슬롯(메인 결정) ─────────

    /** 흐름 1 processing_mode(예시는 CONFIRMED 일반)를 OPEN에서 온 UNKNOWN으로: 막은 사실 factId, 표시 open. */
    static List<SlotEntry> openSlot(NewJudgment j, String factId, boolean open) {
        List<SlotEntry> s = new ArrayList<>();
        for (SlotEntry x : j.slots()) {
            s.add(x.flow() == 1 && "processing_mode".equals(x.slot())
                    ? new SlotEntry(1, x.slot(), "UNKNOWN", List.of(), x.basisFacts(), List.of(factId),
                    open ? List.of(factId) : List.of())
                    : x);
        }
        return s;
    }

    @Test
    void s9_openMarkWithNotFoundFactAccepted() throws Exception {
        String other = UUID.randomUUID().toString();
        ex.load(fx.store, other, true);
        NewJudgment j = ex.judgment(fx.store, other);
        // F-M2는 예시 사안에 행이 없음(묻지 않은 사실). 표시가 있으면 받는다
        long id = fx.store.addJudgment(with(j, null, null, openSlot(j, "F-M2", true), null, null));
        assertThat(fx.jdbc.sql("SELECT status, unknown_facts FROM case_judgment_slots WHERE judgment_id=? AND flow_no=1 "
                + "AND slot_key='processing_mode'").param(id).query().singleRow()).containsEntry("status", "UNKNOWN");
        // 표시가 없으면 옛 규칙 그대로 거부
        assertThatThrownBy(() -> fx.store.addJudgment(with(j, null, null, openSlot(j, "F-M2", false), null, null)))
                .isInstanceOfSatisfying(Rejected.class, r -> assertThat(r.code()).isEqualTo("C16"));
    }

    @Test
    void s9_openMarkWithDecidedFactRejected() {
        // F-M1은 예시 사안에서 CONFIRMED(합치지 않음): OPEN 표시가 있어도 거부
        rejects("C16", () -> fx.store.addJudgment(judgment(j -> with(j, null, null, openSlot(j, "F-M1", true), null, null))));
    }

    // ───────── DB 제약 8: CaseStore를 거치지 않은 쓰기도 막힘 ─────────
    @Nested
    class DbWall {
        static final Set<Integer> CONSTRAINT = Set.of(1048, 1452, 3819);

        void blocked(String sql, Object... args) {
            assertThatThrownBy(() -> fx.jdbc.sql(sql).params(args).update())
                    .isInstanceOfSatisfying(DataAccessException.class, e -> {
                        Throwable c = e.getMostSpecificCause();
                        assertThat(c).isInstanceOf(SQLException.class);
                        assertThat(((SQLException) c).getErrorCode()).isIn(CONSTRAINT);
                    });
            assertThat(fx.counts(cid)).isEqualTo(before);
        }

        static final String FACT = """
                INSERT INTO facts (case_id, flow_no, fact_id, rev, status, source_kind, source_turn, source_step, basis_quote,
                                   recorded_at) VALUES (?, ?, ?, 99, 'CONFIRMED', ?, ?, ?, 'x', ?)""";

        @Test
        void c01_factStepMissing() {
            blocked(FACT, cid, 1, "F-T4", "INQUIRY", null, 999, T);
        }

        @Test
        void c04_answerTurnMissing() {
            blocked(FACT, cid, 1, "F-T4", "ANSWER", 99, 8, T);
        }

        @Test
        void c07_factIdMissing() {
            blocked(FACT, cid, 1, "F-ZZ", "INQUIRY", null, 3, T);
        }

        @Test
        void c08_valueOutsideOptions() {
            long row = fx.one("SELECT MIN(fact_row_id) FROM facts WHERE case_id=? AND fact_id='F-T4'", cid);
            blocked("INSERT INTO fact_values (fact_row_id, fact_id, ord, label) VALUES (?, 'F-T4', 9, '어쩌면')", row);
        }

        @Test
        void c10_flowMissing() {
            blocked(FACT, cid, 9, "F-T4", "INQUIRY", null, 3, T);
        }

        @Test
        void c11_casePackageVersionNull() {
            blocked("""
                    INSERT INTO cases (case_id, requester_id, content, state, rule_set_version, package_version, created_at,
                                       updated_at) VALUES (?, '요청자', '문의', 'ASKING', 'v1.0', NULL, ?, ?)""",
                    UUID.randomUUID().toString(), T, T);
        }

        @Test
        void c14_slotValueOutsideOptions() {
            long jid = fx.one("SELECT judgment_id FROM case_judgments WHERE case_id=?", cid);
            blocked("""
                    INSERT INTO case_judgment_slot_values (judgment_id, flow_no, slot_key, ord, value)
                    VALUES (?, 1, 'data_categories', 9, '없는 값')""", jid);
        }

        @Test
        void c17_llmStepWithoutPrompt() {
            blocked("""
                    INSERT INTO steps (case_id, seq, step, actor, name, model_name, temperature, input_json, output_json,
                                       status, attempts, at) VALUES (?, 99, 'A1', 'LLM', 'x', 'm', 0, '{}', '{}', 'OK', 1, ?)""",
                    cid, T);
        }

        @Test
        void storeTurnsDbErrorIntoRejectedDb() {
            // Java 검사를 지나 DB가 막는 경우(같은 흐름 번호 두 번) → Rejected("DB")
            rejects("DB", () -> fx.store.addFlow(new CaseInput.NewFlow(cid, 1, "중복", "a", "b", null, 3)));
        }
    }

    // ───────── 늦은 저장 거절 ─────────
    @Nested
    class LateSave {
        @Test
        void staleInputOrAlreadyDoneStepIsRefused() {
            String id = UUID.randomUUID().toString();
            fx.store.addCase(new NewCase(id, "요청자", "고객 휴대폰 번호를 문자 발송 업체에 맡기려 합니다", null, "v1.0", "v2",
                    "20261028", T));
            CaseState s = fx.reader.load(id).orElseThrow();
            CaseState.InputMark mark = s.inputMark();
            assertThat(mark).isEqualTo(CaseState.InputMark.INQUIRY);
            NewStep a0 = NewStep.code(id, "A0", "입구 판정", Map.of(), Map.of("intake_type", "SPECIFIC"), T, null, "t");
            // 첫 요청이 A0을 씀
            int seq = fx.lock.write(id, mark, "A0", "입구 판정", null, () -> fx.store.addStep(a0));
            assertThat(seq).isEqualTo(1);
            // 같은 입력을 늦게 처리한 두 번째 요청(재시도·동시 요청): 이미 OK → 거절, 쓰지 않음
            assertThatThrownBy(() -> fx.lock.write(id, mark, "A0", "입구 판정", null, () -> fx.store.addStep(a0)))
                    .isInstanceOfSatisfying(TurnConflict.class,
                            t -> assertThat(t.reason()).isEqualTo(TurnConflict.Reason.ALREADY_DONE));
            assertThat(fx.one("SELECT COUNT(*) FROM steps WHERE case_id=?", id)).isEqualTo(1);
            // 예시 사안: 마지막 입력은 턴 10. 턴 8을 처리한 결과는 늦은 저장 → 거절
            assertThatThrownBy(() -> fx.lock.write(cid, new CaseState.InputMark(8, 13), "A1", "선택지 답 기록", 2,
                    () -> fx.store.addStep(NewStep.code(cid, "A1", "선택지 답 기록", Map.of(), Map.of(), T, 2, "t"))))
                    .isInstanceOfSatisfying(TurnConflict.class,
                            t -> assertThat(t.reason()).isEqualTo(TurnConflict.Reason.INPUT_CHANGED));
            assertThat(fx.counts(cid)).isEqualTo(before);
        }

        @Test
        void rejectionInsideLockRollsBackTheWholeStep() {
            String id = UUID.randomUUID().toString();
            fx.store.addCase(new NewCase(id, "요청자", "고객 번호를 업체에 맡김", null, "v1.0", "v2", null, T));
            assertThatThrownBy(() -> fx.lock.write(id, CaseState.InputMark.INQUIRY, "A1", "사실 추출(문의)", null, () -> {
                int seq = fx.store.addStep(NewStep.code(id, "A1", "사실 추출(문의)", Map.of(), Map.of(), T, null, "t"));
                return fx.store.addFact(new NewFact(id, "F-ZZ", List.of(Value.of("예")), "CONFIRMED", "INQUIRY", seq, T,
                        null, null, "업체", List.of(), null, null));
            })).isInstanceOfSatisfying(Rejected.class, r -> assertThat(r.code()).isEqualTo("C07"));
            // 단계 행과 사실을 함께 쓰는 tx라 단계도 남지 않음
            assertThat(fx.one("SELECT COUNT(*) FROM steps WHERE case_id=?", id)).isZero();
        }
    }
}
