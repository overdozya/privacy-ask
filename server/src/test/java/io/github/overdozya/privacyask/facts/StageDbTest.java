package io.github.overdozya.privacyask.facts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.github.overdozya.privacyask.baseline.BaselineStep;
import io.github.overdozya.privacyask.baseline.LedgerCandidates;
import io.github.overdozya.privacyask.intake.IntakeStep;
import io.github.overdozya.privacyask.llm.LlmResult;
import io.github.overdozya.privacyask.record.CaseInput.NewStep;
import io.github.overdozya.privacyask.record.CaseInput.NewTurn;
import io.github.overdozya.privacyask.record.CaseState;
import io.github.overdozya.privacyask.watch.OmissionWatchStep;
import io.github.overdozya.privacyask.watch.OmissionWatchStep.FlowIssues;

/**
 * 06 7절 S6 ②: 단계마다 손으로 쓴 LLM 출력(FakeProvider)으로 저장·업무 검사를 본다. 테스트 MySQL(적재본) + 실제 프롬프트 파일.
 * 업무 검사가 근거 문구(A0·A1·감시)·원장 행 ID와 인용(A3)·허용 값(A1) 위반을 거절하면 1회 다시(RETRY_OK),
 * 두 번 다 거절이면 LLM_FAILED 단계 행만 남는다. 거절된 출력은 사실·흐름 행을 남기지 않는다(검사는 되돌림 실행).
 * 문의 글은 이 테스트가 지은 것이다(평가셋 문장 아님).
 */
class StageDbTest {
    static final String CONTENT = "저희 T 멤버십 회원에게 멤버십 관련 고지사항 문자를 보내는 일을 다음 달부터 국내 문자 발송 대행 업체에"
            + " 맡기려고 합니다. 업체에는 휴대폰 번호만 넘기고, 계약으로 맡긴 일 외에는 못 쓰게 할 예정입니다.";
    static StageFixture fx;

    @BeforeAll
    static void up() {
        assumeThat(StageFixture.available()).as("적재 파일 또는 Docker 없음").isTrue();
        fx = new StageFixture();
    }

    static final String A1_OK = """
            {"flows":[{"flow":1,"label":"고지 문자 발송 위탁","origin":"회사","destination":"국내 문자 발송 대행 업체","service_name":"T 멤버십"}],
             "facts":[
              {"fact":"F-S1","flows":[],"values":[{"label":"서비스명(자유 입력)","detail":"T 멤버십"}],"status":"CONFIRMED","quote":"저희 T 멤버십 회원에게"},
              {"fact":"F-S3","flows":[],"values":[{"label":"날짜","detail":"2026-12-01"}],"status":"INFERRED","quote":"다음 달부터"},
              {"fact":"F-T1","flows":[1],"values":[{"label":"예"}],"status":"CONFIRMED","quote":"국내 문자 발송 대행 업체에 … 맡기려고 합니다"},
              {"fact":"F-T3","flows":[1],"values":[{"label":"예"}],"status":"CONFIRMED","quote":"계약으로 맡긴 일 외에는 못 쓰게 할 예정"},
              {"fact":"F-AD1","flows":[1],"values":[{"label":"문자"}],"status":"CONFIRMED","quote":"고지사항 문자를"}]}""";

    static String a1With(String fact) {
        return """
                {"flows":[{"flow":1,"label":"고지 문자 발송 위탁","origin":"회사","destination":"문자 발송 대행 업체"}],
                 "facts":[%s]}""".formatted(fact);
    }

    /** A1까지 저장된 사안(흐름 1, 서비스 T 멤버십). */
    static String caseAfterA1() {
        String id = fx.newCase(CONTENT, null);
        fx.fake.thenText(A1_OK);
        FactExtractStep.Result r = fx.facts.runInquiry(fx.state(id), fx.deadline());
        assertThat(r.status()).isEqualTo(LlmResult.Status.OK);
        return id;
    }

    @Nested
    class A0 {
        @Test
        void savesSignalsJudgmentAndLinksCallLog() {
            String id = fx.newCase(CONTENT, null);
            fx.fake.thenText("""
                    {"signals":[{"kind":"OWN_CASE","quote":"저희 T 멤버십 회원에게"},{"kind":"ACTION","quote":"문자 발송 대행 업체에 맡기려고"}]}""");
            IntakeStep.Result r = fx.intake.run(fx.state(id), fx.deadline());
            assertThat(r.status()).isEqualTo(LlmResult.Status.OK);
            assertThat(r.decision().intakeType()).isEqualTo("SPECIFIC");
            CaseState st = fx.state(id);
            assertThat(st.caseRow().intakeType()).isEqualTo("SPECIFIC");
            assertThat(st.steps()).extracting(CaseState.StepSummary::name)
                    .containsExactly(IntakeStep.NAME_LLM, IntakeStep.NAME_CODE);
            assertThat(st.steps().get(0).promptId()).isEqualTo("A0_intake");
            assertThat(st.steps().get(0).promptVersion()).isEqualTo(fx.prompts.get("A0_intake").versionTag());
            assertThat(fx.count("SELECT COUNT(*) FROM llm_call_logs WHERE case_id=? AND step_seq=1", id)).isEqualTo(1);
        }

        @Test
        void outOfScopeStoresKind() {
            String id = fx.newCase("어제 저희 쪽에서 회원 안내 메일이 다른 회원에게 잘못 발송됐습니다. 어떻게 해야 하나요?", null);
            fx.fake.thenText("""
                    {"signals":[{"kind":"INCIDENT","quote":"다른 회원에게 잘못 발송됐습니다"},{"kind":"OWN_CASE","quote":"저희 쪽에서"}]}""");
            fx.intake.run(fx.state(id), fx.deadline());
            CaseState.CaseRow c = fx.state(id).caseRow();
            assertThat(c.intakeType()).isEqualTo("OUT_OF_SCOPE");
            assertThat(c.outOfScopeKind()).isEqualTo("BREACH");
        }

        @Test
        void badQuoteRetriesThenOk() {
            String id = fx.newCase(CONTENT, null);
            fx.fake.thenText("{\"signals\":[{\"kind\":\"OWN_CASE\",\"quote\":\"우리 멤버십 서비스\"}]}")
                    .thenText("{\"signals\":[{\"kind\":\"OWN_CASE\",\"quote\":\"저희 T 멤버십\"}]}");
            IntakeStep.Result r = fx.intake.run(fx.state(id), fx.deadline());
            assertThat(r.status()).isEqualTo(LlmResult.Status.RETRY_OK);
            assertThat(fx.state(id).steps().get(0).attempts()).isEqualTo(2);
            assertThat(fx.jdbc.sql("SELECT outcome FROM llm_call_logs WHERE case_id=? ORDER BY call_id").param(id)
                    .query(String.class).list()).containsExactly("CHECK_FAIL", "OK");
        }

        @Test
        void twoBadQuotesIsLlmFailedStepOnly() {
            String id = fx.newCase(CONTENT, null);
            fx.fake.thenText("{\"signals\":[{\"kind\":\"OWN_CASE\",\"quote\":\"없는 글\"}]}")
                    .thenText("{\"signals\":[{\"kind\":\"OWN_CASE\",\"quote\":\"또 없는 글\"}]}");
            IntakeStep.Result r = fx.intake.run(fx.state(id), fx.deadline());
            assertThat(r.failed()).isTrue();
            CaseState st = fx.state(id);
            assertThat(st.steps()).singleElement().satisfies(s -> {
                assertThat(s.status()).isEqualTo("LLM_FAILED");
                assertThat(s.attempts()).isEqualTo(2);
            });
            assertThat(st.caseRow().intakeType()).isNull();
        }
    }

    @Nested
    class A1 {
        @Test
        void savesFlowsFactsAndCaseFields() {
            String id = caseAfterA1();
            CaseState st = fx.state(id);
            assertThat(st.flows()).singleElement().satisfies(f -> assertThat(f.serviceName()).isEqualTo("T 멤버십"));
            assertThat(st.currentFacts()).extracting(CaseState.Fact::factId)
                    .containsExactlyInAnyOrder("F-S1", "F-S3", "F-T1", "F-T3", "F-AD1");
            assertThat(st.current(1, "F-T1").orElseThrow().basisQuote()).isEqualTo("국내 문자 발송 대행 업체에 … 맡기려고 합니다");
            assertThat(st.caseRow().serviceName()).isEqualTo("T 멤버십");
            assertThat(st.caseRow().referenceDate()).isEqualTo(LocalDate.of(2026, 12, 1));
            assertThat(st.caseRow().referenceDateBasis()).isEqualTo("INQUIRY");
            // 검사용 임시 단계는 남지 않는다: 단계는 A1 하나
            assertThat(st.steps()).extracting(CaseState.StepSummary::name).containsExactly(FactExtractStep.NAME_INQUIRY);
        }

        /** 거절될 출력 하나 → 같은 사안에 두 번(LLM_FAILED). 사실·흐름은 남지 않고 실패 단계만. */
        void rejected(String output, String reasonPart) {
            String id = fx.newCase(CONTENT, null);
            fx.fake.thenText(output).thenText(output);
            FactExtractStep.Result r = fx.facts.runInquiry(fx.state(id), fx.deadline());
            assertThat(r.failed()).isTrue();
            assertThat(r.error()).contains(reasonPart);
            assertThat(fx.rows(id)).isEqualTo("1/0/0");
        }

        @Test
        void quoteNotInInquiryIsC03() {
            rejected(a1With("{\"fact\":\"F-T1\",\"flows\":[1],\"values\":[{\"label\":\"예\"}],\"status\":\"CONFIRMED\",\"quote\":\"업체에 위탁합니다\"}"),
                    "C03");
        }

        @Test
        void labelOutsideOptionsIsC08() {
            rejected(a1With("{\"fact\":\"F-T1\",\"flows\":[1],\"values\":[{\"label\":\"맞음\"}],\"status\":\"CONFIRMED\",\"quote\":\"맡기려고\"}"),
                    "C08");
        }

        @Test
        void detailMissingIsC08() {
            rejected(a1With("{\"fact\":\"F-S1\",\"flows\":[],\"values\":[{\"label\":\"서비스명(자유 입력)\"}],\"status\":\"CONFIRMED\",\"quote\":\"T 멤버십\"}"),
                    "C08");
        }

        @Test
        void multipleValuesOnSingleFactIsC08() {
            rejected(a1With("{\"fact\":\"F-T1\",\"flows\":[1],\"values\":[{\"label\":\"예\"},{\"label\":\"아니오(우리 회사 안에서만)\"}],\"status\":\"CONFIRMED\",\"quote\":\"맡기려고\"}"),
                    "C08");
        }

        @Test
        void commonFactWithFlowIsRejected() {
            rejected(a1With("{\"fact\":\"F-S1\",\"flows\":[1],\"values\":[{\"label\":\"서비스명(자유 입력)\",\"detail\":\"T 멤버십\"}],\"status\":\"CONFIRMED\",\"quote\":\"T 멤버십\"}"),
                    "공통 사실");
        }

        @Test
        void unknownFlowIsRejected() {
            rejected(a1With("{\"fact\":\"F-T1\",\"flows\":[2],\"values\":[{\"label\":\"예\"}],\"status\":\"CONFIRMED\",\"quote\":\"맡기려고\"}"),
                    "없는 흐름 2");
        }

        @Test
        void ledgerFactIsRejected() {
            rejected(a1With("{\"fact\":\"L1\",\"flows\":[1],\"values\":[{\"label\":\"명시 일치\"}],\"status\":\"CONFIRMED\",\"quote\":\"맡기려고\"}"),
                    "원장 사실");
        }

        @Test
        void agentDoesNotPickUnknown() {
            rejected(a1With("{\"fact\":\"F-C2\",\"flows\":[1],\"values\":[{\"label\":\"잘 모르겠음\"}],\"status\":\"CONFIRMED\",\"quote\":\"맡기려고\"}"),
                    "미확인은 현업이 고를 때만");
        }

        @Test
        void unknownFactIdIsRejected() {
            rejected(a1With("{\"fact\":\"F-Z9\",\"flows\":[1],\"values\":[{\"label\":\"예\"}],\"status\":\"CONFIRMED\",\"quote\":\"맡기려고\"}"),
                    "없는 사실 ID");
        }

        @Test
        void flowNumberingAndEmptyFlows() {
            rejected("{\"flows\":[{\"flow\":2,\"label\":\"a\",\"origin\":\"b\",\"destination\":\"c\"}],\"facts\":[]}", "차례");
            rejected("{\"flows\":[],\"facts\":[]}", "흐름이 없음");
        }

        @Test
        void rejectedThenGoodIsRetryOk() {
            String id = fx.newCase(CONTENT, null);
            fx.fake.thenText(a1With("{\"fact\":\"F-T1\",\"flows\":[1],\"values\":[{\"label\":\"예\"}],\"status\":\"CONFIRMED\",\"quote\":\"위탁\"}"))
                    .thenText(A1_OK);
            FactExtractStep.Result r = fx.facts.runInquiry(fx.state(id), fx.deadline());
            assertThat(r.status()).isEqualTo(LlmResult.Status.RETRY_OK);
            assertThat(r.factRows()).isEqualTo(5);
            // 다시 요청할 때 거절 이유(C03)를 user 글 끝에 붙였다
            assertThat(fx.fake.requests().getLast().user()).contains("C03");
        }

        @Test
        void freeAnswerExtractsFromAnswerText() {
            String id = caseAfterA1();
            int a2 = fx.store.addStep(NewStep.code(id, "A2", "다음 질문 고르기", Map.of(), Map.of(), fx.clock.nowUtc(), null, "test"));
            int q = fx.store.addTurn(NewTurn.question(id, a2, 1, "F-C2", 1, "서버가 해외에 있나요?",
                    List.of("예(나라)", "아니오", "잘 모르겠음"), fx.clock.nowUtc()));
            int ans = fx.store.addTurn(NewTurn.answer(id, a2, q, "서버는 일본 도쿄 리전에 있다고 들었습니다", List.of(), fx.clock.nowUtc()));
            fx.fake.thenText("""
                    {"flows":[],"facts":[{"fact":"F-C2","flows":[1],"values":[{"label":"예(나라)","detail":"일본"}],"status":"CONFIRMED","quote":"서버는 일본 도쿄 리전에 있다고"}]}""");
            FactExtractStep.Result r = fx.facts.runAnswer(fx.state(id), ans, fx.deadline());
            assertThat(r.status()).isEqualTo(LlmResult.Status.OK);
            CaseState.Fact f = fx.state(id).current(1, "F-C2").orElseThrow();
            assertThat(f.sourceKind()).isEqualTo("ANSWER");
            assertThat(f.sourceTurn()).isEqualTo(ans);
            assertThat(f.values().get(0).detail()).isEqualTo("일본");
            // 답 A1 입력에는 질문과 이미 있는 흐름이 들어간다
            assertThat(fx.fake.requests().getLast().user()).contains("\"known_flows\"").contains("서버가 해외에 있나요?");
        }
    }

    @Nested
    class Answer {
        @Test
        void choiceAnswerBecomesFactWithoutLlm() {
            String id = caseAfterA1();
            int a2 = fx.store.addStep(NewStep.code(id, "A2", "다음 질문 고르기", Map.of(), Map.of(), fx.clock.nowUtc(), null, "test"));
            int q = fx.store.addTurn(NewTurn.question(id, a2, 1, "F-T4", 1, "또 다른 회사에 맡기나요?",
                    List.of("예", "아니오", "잘 모르겠음"), fx.clock.nowUtc()));
            int ans = fx.store.addTurn(NewTurn.answer(id, a2, q, "아니오", List.of("아니오"), fx.clock.nowUtc()));
            int calls = fx.fake.requests().size();
            AnswerApplier.Result r = fx.answers.apply(fx.state(id), ans, null);
            assertThat(r.status()).isEqualTo("CONFIRMED");
            assertThat(fx.fake.requests()).hasSize(calls);
            CaseState.Fact f = fx.state(id).current(1, "F-T4").orElseThrow();
            assertThat(f.basisQuote()).isEqualTo("아니오");
            assertThat(f.sourceTurn()).isEqualTo(ans);

            int a2b = fx.store.addStep(NewStep.code(id, "A2", "다음 질문 고르기", Map.of(), Map.of(), fx.clock.nowUtc(), null, "test"));
            int q2 = fx.store.addTurn(NewTurn.question(id, a2b, 1, "F-C2", 1, "서버가 해외에 있나요?",
                    List.of("예(나라)", "아니오", "잘 모르겠음"), fx.clock.nowUtc()));
            int ans2 = fx.store.addTurn(NewTurn.answer(id, a2b, q2, "잘 모르겠음", List.of("잘 모르겠음"), fx.clock.nowUtc()));
            assertThat(fx.answers.apply(fx.state(id), ans2, null).status()).isEqualTo("UNKNOWN");
        }

        @Test
        void invalidAnswersAreNamed() {
            AnswerApplier a = fx.answers;
            assertThat(a.invalidReason("F-T4", List.of("아니오"), null)).isNull();
            assertThat(a.invalidReason("F-C2", List.of("예(나라)"), "미국")).isNull();
            assertThat(a.invalidReason("F-T4", List.of("몰라요"), null)).contains("보기에 없음");
            assertThat(a.invalidReason("F-T4", List.of("예", "아니오"), null)).contains("하나만");
            assertThat(a.invalidReason("F-AD1", List.of("문자", "잘 모르겠음"), null)).contains("같이 고를 수 없음");
            assertThat(a.invalidReason("F-C2", List.of("예(나라)"), " ")).contains("내용");
            assertThat(a.invalidReason("L1", List.of("명시 일치"), null)).contains("원장 사실");
            assertThat(a.invalidReason("F-T4", List.of(), null)).contains("없음");
        }

        @Test
        void applyRefusesInvalidAnswer() {
            String id = caseAfterA1();
            int a2 = fx.store.addStep(NewStep.code(id, "A2", "다음 질문 고르기", Map.of(), Map.of(), fx.clock.nowUtc(), null, "test"));
            int q = fx.store.addTurn(NewTurn.question(id, a2, 1, "F-C2", 1, "서버가 해외에 있나요?",
                    List.of("예(나라)", "아니오", "잘 모르겠음"), fx.clock.nowUtc()));
            int ans = fx.store.addTurn(NewTurn.answer(id, a2, q, "예(나라)", List.of("예(나라)"), fx.clock.nowUtc()));
            String before = fx.rows(id);
            assertThatThrownBy(() -> fx.answers.apply(fx.state(id), ans, null)).hasMessageContaining("INVALID_ANSWER");
            assertThat(fx.rows(id)).isEqualTo(before);
        }
    }

    @Nested
    class Baseline {
        /** 후보가 있는 묶음마다: L1은 첨부1-0040 명시, 나머지는 없음. */
        static String cites(LedgerCandidates.Candidates c, String l1Match, String l1Row, String l1Quote) {
            List<String> items = new ArrayList<>();
            for (LedgerCandidates.Group g : c.groups()) {
                if (g.rows().isEmpty()) {
                    continue;
                }
                for (int n : g.flows()) {
                    if (g.factId().equals("L1")) {
                        items.add("{\"flow\":%d,\"fact\":\"L1\",\"match\":\"%s\",\"rows\":[{\"row_id\":\"%s\",\"quote\":\"%s\"}]}"
                                .formatted(n, l1Match, l1Row, l1Quote));
                    } else {
                        items.add("{\"flow\":%d,\"fact\":\"%s\",\"match\":\"없음\",\"rows\":[],\"reason\":\"후보에 그 업체가 없음\"}"
                                .formatted(n, g.factId()));
                    }
                }
            }
            return "{\"cites\":[" + String.join(",", items) + "]}";
        }

        @Test
        void candidatesCitesVerdictAndLedgerFacts() {
            String id = caseAfterA1();
            CaseState st = fx.state(id);
            LedgerCandidates.Candidates c = LedgerCandidates.enumerate(st, List.of(1), fx.snap);
            assertThat(c.group("L1", 1).orElseThrow().rowIds()).contains("첨부1-0040");
            // 줄바꿈 낀 행 글도 공백 빼고 견준다("고지사항 전달, 본인 의사\n확인")
            fx.fake.thenText(cites(c, "명시", "첨부1-0040", "멤버십 관련 고지사항 전달, 본인 의사 확인"));
            BaselineStep.Result r = fx.baseline.run(st, fx.deadline());
            assertThat(r.status()).isEqualTo(LlmResult.Status.OK);
            assertThat(r.llmCalled()).isTrue();
            CaseState after = fx.state(id);
            CaseState.Fact l1 = after.current(1, "L1").orElseThrow();
            assertThat(l1.labels()).containsExactly("명시 일치");
            assertThat(l1.status()).isEqualTo("MATCHED");
            assertThat(l1.ledgerRows()).containsExactly("첨부1-0040");
            assertThat(l1.ledgerVersion()).isEqualTo(c.ledgerVersion());
            // 원장 사실은 흐름마다 넷 다 적힌다(후보 없는 묶음은 LLM 없이 '없음')
            assertThat(after.currentFacts().stream().filter(f -> f.factId().startsWith("L")).map(CaseState.Fact::factId))
                    .containsExactlyInAnyOrder("L1", "L2", "L3", "L4");
            assertThat(after.steps().stream().filter(s -> s.step().equals("A3")).map(CaseState.StepSummary::name))
                    .containsExactly(BaselineStep.NAME_CANDIDATES, BaselineStep.NAME_CITE, BaselineStep.NAME_VERDICT);
            assertThat(fx.baseline.pendingFlows(after)).isEmpty();
        }

        @Test
        void broadMatchIsInferred() {
            String id = caseAfterA1();
            CaseState st = fx.state(id);
            LedgerCandidates.Candidates c = LedgerCandidates.enumerate(st, List.of(1), fx.snap);
            fx.fake.thenText(cites(c, "포괄", "첨부1-0040", "원활한 의사소통 경로 확보"));
            fx.baseline.run(st, fx.deadline());
            CaseState.Fact l1 = fx.state(id).current(1, "L1").orElseThrow();
            assertThat(l1.labels()).containsExactly("포괄 일치");
            assertThat(l1.status()).isEqualTo("INFERRED");
        }

        void rejected(String bad, String reasonPart) {
            String id = caseAfterA1();
            CaseState st = fx.state(id);
            String before = fx.rows(id);
            fx.fake.thenText(bad).thenText(bad);
            BaselineStep.Result r = fx.baseline.run(st, fx.deadline());
            assertThat(r.failed()).isTrue();
            assertThat(r.error()).contains(reasonPart);
            // 후보 열거 + 실패한 인용 단계만 더해지고 사실은 없다
            assertThat(fx.rows(id)).isEqualTo(before.replaceFirst("^1/", "3/"));
        }

        @Test
        void rowOutsideCandidatesIsRejected() {
            String id = caseAfterA1();
            LedgerCandidates.Candidates c = LedgerCandidates.enumerate(fx.state(id), List.of(1), fx.snap);
            rejected(cites(c, "명시", "첨부1-0001", "본인 확인 서비스"), "후보 밖");
        }

        @Test
        void quoteNotInRowIsRejected() {
            String id = caseAfterA1();
            LedgerCandidates.Candidates c = LedgerCandidates.enumerate(fx.state(id), List.of(1), fx.snap);
            rejected(cites(c, "명시", "첨부1-0040", "고지 문자 발송 위탁"), "그 행 글에 없음");
        }

        @Test
        void missingPairAndWrongMatchAreRejected() {
            rejected("{\"cites\":[]}", "결과 없음");
            String id = caseAfterA1();
            LedgerCandidates.Candidates c = LedgerCandidates.enumerate(fx.state(id), List.of(1), fx.snap);
            rejected(cites(c, "명시", "첨부1-0040", "멤버십 관련 고지사항 전달").replace("\"match\":\"없음\",\"rows\":[]",
                    "\"match\":\"명시\",\"rows\":[]"), "행 없음");
        }

        @Test
        void noServiceMeansNoLedgerFacts() {
            String id = fx.newCase("저희 팀이 고객 상담 녹취를 외부 업체에 맡기려 합니다.", null);
            fx.fake.thenText("""
                    {"flows":[{"flow":1,"label":"상담 녹취 위탁","origin":"회사","destination":"외부 업체"}],
                     "facts":[{"fact":"F-T1","flows":[1],"values":[{"label":"예"}],"status":"CONFIRMED","quote":"외부 업체에 맡기려 합니다"}]}""");
            fx.facts.runInquiry(fx.state(id), fx.deadline());
            int calls = fx.fake.requests().size();
            BaselineStep.Result r = fx.baseline.run(fx.state(id), fx.deadline());
            assertThat(r.llmCalled()).isFalse();
            assertThat(fx.fake.requests()).hasSize(calls);
            assertThat(r.candidates().noService()).containsExactly(1);
            assertThat(r.verdicts()).isEmpty();
            assertThat(fx.state(id).steps().getLast().name()).isEqualTo(BaselineStep.NAME_VERDICT);
        }
    }

    @Nested
    class Watch {
        static final List<FlowIssues> FOUND = List.of(new FlowIssues(1, List.of("처리위탁")));

        @Test
        void flagIsShownNotAdded() {
            String id = caseAfterA1();
            fx.fake.thenText("""
                    {"flags":[{"kind":"MISSED","issue":"광고성 정보 전송","flow":1,"quote":"고지사항 문자를","reason":"문자 발송"}]}""");
            OmissionWatchStep.Result r = fx.watch.run(fx.state(id), FOUND, fx.deadline());
            assertThat(r.status()).isEqualTo(LlmResult.Status.OK);
            assertThat(r.watchFlag()).isTrue();
            assertThat(fx.state(id).steps().getLast().name()).isEqualTo(OmissionWatchStep.NAME);
            // 쟁점 목록은 사안 꾸러미 판의 14개가 입력으로 간다
            assertThat(fx.fake.requests().getLast().user()).contains("\"catalog\"").contains("추가적 이용·제공");
        }

        @Test
        void noFlagsIsNoWatchFlag() {
            String id = caseAfterA1();
            fx.fake.thenText("{\"flags\":[]}");
            assertThat(fx.watch.run(fx.state(id), FOUND, fx.deadline()).watchFlag()).isFalse();
        }

        void rejected(String bad, String reasonPart) {
            String id = caseAfterA1();
            fx.fake.thenText(bad).thenText(bad);
            OmissionWatchStep.Result r = fx.watch.run(fx.state(id), FOUND, fx.deadline());
            assertThat(r.failed()).isTrue();
            assertThat(r.error()).contains(reasonPart);
        }

        @Test
        void rejectsQuoteIssueAndAlreadyFound() {
            rejected("{\"flags\":[{\"kind\":\"MISSED\",\"issue\":\"국외 이전\",\"flow\":1,\"quote\":\"해외 서버\",\"reason\":\"x\"}]}",
                    "문의·답 글에 없음");
            rejected("{\"flags\":[{\"kind\":\"MISSED\",\"issue\":\"재위탁\",\"flow\":1,\"quote\":\"맡기려고\",\"reason\":\"x\"}]}",
                    "쟁점 목록 밖");
            rejected("{\"flags\":[{\"kind\":\"MISSED\",\"issue\":\"처리위탁\",\"flow\":1,\"quote\":\"맡기려고\",\"reason\":\"x\"}]}",
                    "이미 찾은 쟁점");
            rejected("{\"flags\":[{\"kind\":\"OUTSIDE\",\"issue\":\"국외 이전\",\"quote\":\"맡기려고\",\"reason\":\"x\"}]}",
                    "목록 안");
        }
    }

    @Test
    void inputsCarryNoKnowledgeInPromptFiles() {
        // 06 4.5: 사실 목록·보기는 실행 중 스냅샷에서 입력으로 넣는다(프롬프트 파일에는 없다)
        assertThat(fx.prompts.get("A1_facts").system()).doesNotContain("F-T1").doesNotContain("첨부1");
        Map<String, Object> in = new LinkedHashMap<>(fx.facts.input(fx.state(caseAfterA1()), FactExtractStep.Source.INQUIRY, null));
        assertThat(fx.facts.sheet().size()).isEqualTo(fx.snap.catalog().facts().size() - 4);
        assertThat(in).containsKeys("source", "today", "text", "facts");
    }
}
