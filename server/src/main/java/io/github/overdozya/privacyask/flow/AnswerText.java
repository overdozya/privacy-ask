package io.github.overdozya.privacyask.flow;

import java.util.List;

/**
 * 답 턴 글(turns.text) 짜기와 읽기. 턴 글 하나에 보기·보기 내용(DETAIL)·자유 답을 담아, 다시 읽은 DB 상태만으로 다음 할 일을 정한다(06 3.4).
 * <pre>
 * 보기1, 보기2          ← 고른 보기(없으면 이 줄 없음)
 * 내용: 미국            ← DETAIL 보기의 자유 입력(있을 때만)
 * 자유 답 글…           ← text(있을 때만, 여러 줄 가능)
 * </pre>
 * 보기만이면 글 = 보기 줄(AnswerApplier 근거 문구 = 고른 보기 글). 자유 답이 있으면 A1이 이 턴 글 전체를 읽는다.
 */
public final class AnswerText {
    static final String DETAIL_HEAD = "내용: ";

    private AnswerText() {}

    public record Parsed(String detail, String freeText) {
        public boolean hasFreeText() {
            return freeText != null && !freeText.isBlank();
        }
    }

    public static String compose(List<String> choices, String detail, String text) {
        StringBuilder b = new StringBuilder();
        if (!choices.isEmpty()) {
            b.append(String.join(", ", choices));
        }
        if (detail != null && !detail.isBlank()) {
            if (!b.isEmpty()) {
                b.append('\n');
            }
            b.append(DETAIL_HEAD).append(detail.strip().replace('\n', ' '));
        }
        if (text != null && !text.isBlank()) {
            if (!b.isEmpty()) {
                b.append('\n');
            }
            b.append(text.strip());
        }
        return b.toString();
    }

    public static Parsed parse(List<String> choices, String turnText) {
        String rest = turnText == null ? "" : turnText;
        if (!choices.isEmpty()) {
            String head = String.join(", ", choices);
            if (!rest.startsWith(head)) {
                return new Parsed(null, rest);
            }
            rest = rest.substring(head.length());
            if (rest.startsWith("\n")) {
                rest = rest.substring(1);
            }
        }
        String detail = null;
        if (rest.startsWith(DETAIL_HEAD)) {
            int nl = rest.indexOf('\n');
            detail = (nl < 0 ? rest : rest.substring(0, nl)).substring(DETAIL_HEAD.length());
            rest = nl < 0 ? "" : rest.substring(nl + 1);
        }
        return new Parsed(detail, rest.isBlank() ? null : rest);
    }
}
