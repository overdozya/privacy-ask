package io.github.overdozya.privacyask.answer;

import java.util.List;

/** A5 구체 답변 LLM 출력(prompts/A5_answer.schema.json). 인용은 원문 키(cites)와 원장 행 ID(ledgerRows)로만. */
public record AnswerOutput(List<IssueOut> issues, List<GapOut> gaps) {
    public static final String FACT_MISSING = "FACT_MISSING", INTERPRETATION = "INTERPRETATION",
            OUTSIDE_PACKAGE = "OUTSIDE_PACKAGE";

    public record IssueOut(int flow, String issue, String opinion, List<String> actions, List<String> cites,
                           List<String> ledgerRows, boolean conclusive) {
        public IssueOut {
            actions = actions == null ? List.of() : List.copyOf(actions);
            cites = cites == null ? List.of() : List.copyOf(cites);
            ledgerRows = ledgerRows == null ? List.of() : List.copyOf(ledgerRows);
        }
    }

    public record GapOut(String kind, Integer flow, String issue, List<String> facts, String note) {
        public GapOut {
            facts = facts == null ? List.of() : List.copyOf(facts);
        }
    }

    public AnswerOutput {
        issues = issues == null ? List.of() : List.copyOf(issues);
        gaps = gaps == null ? List.of() : List.copyOf(gaps);
    }
}
