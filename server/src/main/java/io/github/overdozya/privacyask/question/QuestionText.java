package io.github.overdozya.privacyask.question;

import java.util.List;

import io.github.overdozya.privacyask.knowledge.Catalog;

/**
 * 질문 글과 보기(06 3.3 A2). 보기는 늘 fact_options 글 그대로(허용 값만 받음). 흐름 사실이면 앞에 [받는 쪽]을 붙인다(예시 사안 턴).
 * 두 번째 물음(askNo 2, 04 1.9 '다시')은 코드 틀: 같은 질문 + 보기 앞 3개('잘 모르겠음' 뺌)를 "예:"로 + '잘 모르겠음' 안내.
 */
public final class QuestionText {
    public static final String UNKNOWN_GUIDE = "정말 모르면 '잘 모르겠음'을 골라 주세요. 검토 요청서에 '현업 미확인'으로 남깁니다.";

    /** 턴 QUESTION 행에 넣을 글과 보기. */
    public record Text(String text, List<String> choices) {}

    private QuestionText() {}

    public static Text of(Catalog.Fact fact, int askNo, String flowDestination) {
        String head = flowDestination == null || flowDestination.isBlank() ? "" : "[" + flowDestination + "] ";
        List<String> choices = fact.options().stream().map(Catalog.Option::label).toList();
        String q = head + fact.question();
        if (askNo >= 2) {
            List<String> ex = fact.options().stream().filter(o -> !"UNKNOWN".equals(o.kind())).limit(3)
                    .map(Catalog.Option::label).toList();
            q = q + "\n예: " + String.join(" / ", ex) + "\n" + UNKNOWN_GUIDE;
        }
        return new Text(q, choices);
    }
}
