package io.github.overdozya.privacyask.answer;

import java.util.List;

/**
 * A5 구체 답변 LLM 출력(prompts/A5_answer.schema.json). 인용은 원문 키(cites)와 원장 행 ID(ledgerRows)로만.
 * toolRequests(S10, 없어도 됨): A6 도구 요청(부를지·검색어는 모델, 실행·상한은 코드).
 */
public record AnswerOutput(List<IssueOut> issues, List<GapOut> gaps, List<ToolRequest> toolRequests) {
    public static final String FACT_MISSING = "FACT_MISSING", INTERPRETATION = "INTERPRETATION",
            OUTSIDE_PACKAGE = "OUTSIDE_PACKAGE", DETAIL_NEEDED = "DETAIL_NEEDED";

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

    /** tool = search_guides(query 필수, issue·flow는 그 gap의 쟁점) 또는 read_section(section_id 필수). */
    public record ToolRequest(String tool, String query, Integer flow, String issue, String sectionId, String why) {}

    public AnswerOutput {
        issues = issues == null ? List.of() : List.copyOf(issues);
        gaps = gaps == null ? List.of() : List.copyOf(gaps);
        toolRequests = toolRequests == null ? List.of() : List.copyOf(toolRequests);
    }

    public AnswerOutput(List<IssueOut> issues, List<GapOut> gaps) {
        this(issues, gaps, List.of());
    }
}
