package io.github.overdozya.privacyask.record;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * CaseStore에 넣는 것(store.py add_* 인자와 같다). 시각은 부르는 쪽이 넘긴다(AppClock.nowUtc(), UTC):
 * 같은 입력이면 같은 DB. 단계 패키지 사이에 오가는 것은 이 타입과 CaseState뿐이다(06 1.5).
 */
public final class CaseInput {
    private CaseInput() {}

    /** 사안 시작: 판 셋(규칙 세트·꾸러미·처리방침)을 고정한다(C11·C12). */
    public record NewCase(String caseId, String requesterId, String content, String serviceName,
                          String ruleSetVersion, String packageVersion, String ledgerVersion, LocalDateTime at) {}

    /**
     * 단계 기록. actor LLM이면 promptId·promptVersion·modelName·temperature가 모두 있어야 한다(C17).
     * input·output은 JSON으로 바꿀 수 있는 값(Map·record·JsonNode·JSON 글이 아닌 String은 JSON 문자열이 된다).
     */
    public record NewStep(String caseId, String step, String actor, String name, Object input, Object output,
                          LocalDateTime at, Integer flowNo, String promptId, String promptVersion, String modelName,
                          BigDecimal temperature, String codeVersion, String status, int attempts) {
        public static NewStep code(String caseId, String step, String name, Object input, Object output,
                                   LocalDateTime at, Integer flowNo, String codeVersion) {
            return new NewStep(caseId, step, "CODE", name, input, output, at, flowNo, null, null, null, null,
                    codeVersion, "OK", 1);
        }

        public static NewStep llm(String caseId, String step, String name, Object input, Object output,
                                  LocalDateTime at, Integer flowNo, String promptId, String promptVersion,
                                  String modelName, BigDecimal temperature, String codeVersion, String status,
                                  int attempts) {
            return new NewStep(caseId, step, "LLM", name, input, output, at, flowNo, promptId, promptVersion,
                    modelName, temperature, codeVersion, status, attempts);
        }
    }

    public record NewFlow(String caseId, int flowNo, String label, String origin, String destination,
                          String serviceName, int createdStep) {}

    /** QUESTION: role AGENT, factId·askNo, replyTo 없음. ANSWER: role USER, replyTo = 질문 턴(흐름·사실은 질문에서). */
    public record NewTurn(String caseId, String role, String kind, String text, List<String> choices, int stepSeq,
                          LocalDateTime at, Integer flowNo, String factId, Integer askNo, Integer replyTo) {
        public static NewTurn question(String caseId, int stepSeq, Integer flowNo, String factId, int askNo,
                                       String text, List<String> choices, LocalDateTime at) {
            return new NewTurn(caseId, "AGENT", "QUESTION", text, choices, stepSeq, at, flowNo, factId, askNo, null);
        }

        public static NewTurn answer(String caseId, int stepSeq, int replyTo, String text, List<String> choices,
                                     LocalDateTime at) {
            return new NewTurn(caseId, "USER", "ANSWER", text, choices, stepSeq, at, null, null, null, replyTo);
        }
    }

    /** 사실 값 하나: 보기 라벨 + 자유 입력(보기 종류가 DETAIL일 때만). */
    public record Value(String label, String detail) {
        public static Value of(String label) {
            return new Value(label, null);
        }
    }

    /** values가 비면 NOT_FOUND. sourceKind INQUIRY·ANSWER·LEDGER. quote 조각은 " … "로 잇는다. */
    public record NewFact(String caseId, String factId, List<Value> values, String status, String sourceKind,
                          int sourceStep, LocalDateTime at, Integer flowNo, Integer sourceTurn, String quote,
                          List<String> ledgerRows, String ledgerVersion, String note) {}

    public record PromptRef(String promptId, String version) {}

    /**
     * basisFacts = facts.fact_row_id. unknownFacts = 현업 미확인(UNKNOWN) 사실 ID.
     * openFacts(S9, 메인 결정): 판정 때 OPEN으로 남아 UNKNOWN으로 적는 슬롯의 '막은 사실'(unknownFacts 안). 이 사실만은 C16이 지금 판이
     * UNKNOWN이 아니어도(NOT_FOUND·행 없음) 받는다. 값이 있는 정해진 사실이면 그래도 거부한다. 비면 옛 규칙 그대로.
     */
    public record SlotEntry(int flow, String slot, String status, List<String> values, List<Long> basisFacts,
                            List<String> unknownFacts, List<String> openFacts) {
        public SlotEntry {
            openFacts = openFacts == null ? List.of() : List.copyOf(openFacts);
        }

        public SlotEntry(int flow, String slot, String status, List<String> values, List<Long> basisFacts,
                         List<String> unknownFacts) {
            this(flow, slot, status, values, basisFacts, unknownFacts, List.of());
        }
    }

    public record IssueEntry(int flow, String issue, String packageId, String grade, List<String> ruleIds,
                             List<String> conditions, List<String> interpFlags, boolean conclusive, String opinion,
                             String actions, boolean visible) {}

    /** issue = issues 목록 번호(0부터) 또는 null. kind LAW_UNIT·ANNEX·GUIDE_SECTION·LEDGER_ROW. */
    public record CitationEntry(Integer issue, String kind, String key, String textHash, String ledgerVersion,
                                String origin) {}

    public record NewJudgment(String caseId, int stepSeq, Integer judgedTurn, java.time.LocalDate referenceDate,
                              String ruleSetVersion, String packageVersion, String ledgerVersion,
                              String factMapSha256, String modelName, BigDecimal temperature,
                              List<PromptRef> promptVersions, String caseGrade, String exitType,
                              List<String> exitReasons, boolean conclusive, Object flags, Object reviewBundle,
                              String premiseNote, LocalDateTime createdAt, List<SlotEntry> slots,
                              List<IssueEntry> issues, List<CitationEntry> citations) {
        public static final String PREMISE_NOTE = "제시된 사실관계를 전제로 한 의견";
    }
}
