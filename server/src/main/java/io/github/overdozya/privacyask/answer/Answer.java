package io.github.overdozya.privacyask.answer;

import java.util.List;

/**
 * 답(A5) — 응답 TurnResult.result.answer와 A5 단계 output_json에 그대로 들어가는 모양. 원문 요약·해시·시행 예정은 코드가 키로 붙인 것이고,
 * 모델이 쓴 것은 opinion·actions·gap note·pick why·reason뿐이다.
 * kind SPECIFIC: issues(쟁점별 의견·조치·인용)·gaps. kind GENERAL(G2): picks·noBasis·reason·guides, 그리고 두 번째 호출의
 * 답 문장 answerText·answerCites(S8b, 고른 조 원문만 보고 씀).
 * conclusive: 사안(또는 일반 답) 전체가 확정인가(코드가 정함: 쟁점이 모두 확정이고 사안 전체 gap 없음).
 * addedReasons: 예비 출구에 더할 이관 사유(과거 기준일·해석 필요·꾸러미 밖·근거 없음). 인용 원문 대조·현행 검사·최종 출구는 A7(S9).
 */
public record Answer(String kind, String referenceDate, String referenceDateBasis, String openDate,
                     boolean pastReferenceDate, boolean conclusive, List<IssueAnswer> issues, List<Gap> gaps,
                     List<Pick> picks, Boolean noBasis, String reason, List<Source> sources, List<LedgerRow> ledgerRows,
                     List<Guide> guides, List<AddedReason> addedReasons, String answerText, List<String> answerCites) {
    public static final String SPECIFIC = "SPECIFIC", GENERAL = "GENERAL";
    /** 더하는 이관 사유 코드. */
    public static final String PAST_REFERENCE_DATE = "PAST_REFERENCE_DATE", GAP_INTERPRETATION = "GAP_INTERPRETATION",
            GAP_OUTSIDE_PACKAGE = "GAP_OUTSIDE_PACKAGE", NO_BASIS = "NO_BASIS";

    /**
     * 쟁점 하나의 답. conclusiveModel = 모델 표시, conclusive = 코드가 본 것(해석 쟁점이 켜졌거나 그 쟁점·사안 전체에 gap이 있으면 거짓),
     * notConclusive = 거짓인 까닭. checks = '확인 필요'(DETAIL_NEEDED gap: 답은 했지만 무엇인지 더 봐야 하는 사실, S8b) — 표시만.
     */
    public record IssueAnswer(int flow, String issue, String packageId, String grade, String opinion,
                              List<String> actions, List<String> cites, List<String> ledgerRows, boolean conclusiveModel,
                              boolean conclusive, List<String> notConclusive, List<String> checks) {}

    /** handled: 이 gap을 무엇으로 다뤘나(QUESTION 질문으로 돌아감 · REASON 이관 사유 · NOTE 확인 필요 표시만 · NONE 그대로 감). */
    public record Gap(String kind, Integer flow, String issue, List<String> facts, String note, String handled) {}

    /** 일반 답 G2: 고른 조. */
    public record Pick(String key, String label, String title, String lawName, String why) {}

    /**
     * 인용된(또는 일반 답에 붙인) 원문 단위. excerpt = 원문 앞부분(구체) 또는 전문(일반). upcoming = 기준일 뒤 시행 예정 판의 시행일.
     */
    public record Source(String key, String kind, String doc, String label, String title, String role,
                         String textHash, String effectiveFrom, List<String> upcoming, boolean answerBasis,
                         String usePolicy, String excerpt) {}

    public record LedgerRow(String rowId, String attachment, String textHash, String excerpt) {}

    /** 일반 답에 붙인 안내서 절(고른 조가 핵심인 꾸러미의 조건 없는 절). */
    public record Guide(String sectionId, String packageId, String doc, String path, String title, String textHash) {}

    public record AddedReason(String code, Integer flow, String detail) {}

    /** 일반 답에 답 문장을 붙인 판(S8b). */
    public Answer withText(String text, List<String> cites) {
        return new Answer(kind, referenceDate, referenceDateBasis, openDate, pastReferenceDate, conclusive, issues, gaps,
                picks, noBasis, reason, sources, ledgerRows, guides, addedReasons, text, cites == null ? null : List.copyOf(cites));
    }
}
