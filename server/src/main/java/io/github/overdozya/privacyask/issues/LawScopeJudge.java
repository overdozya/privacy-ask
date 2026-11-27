package io.github.overdozya.privacyask.issues;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

import io.github.overdozya.privacyask.knowledge.Documents;

/**
 * 적용 법령 판정(06 3.3 A3 적용 법령): 문의·답에 나온 법령 이름(A0 (마) 신호, F-B2 조문 등, 부르는 쪽이 모음)을
 * kb_documents.law_name에 맞춘다. 즉답 근거(use_policy ANSWER_BASIS)인 법령이 아니거나(REFERENCE·OUT) KB에 없으면
 * 이관 사유 OUTSIDE_KB_LAW. 이름은 공백을 빼고, 글 앞부분이 가장 길게 맞는 법령으로 본다('개인정보 보호법 시행령 제14조의2'
 * → 시행령). LLM을 부르지 않는다.
 */
public final class LawScopeJudge {
    private static final Pattern WS = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    public static final String ANSWER_BASIS = "ANSWER_BASIS";

    private final List<Documents.Doc> laws;

    public LawScopeJudge(Collection<Documents.Doc> docs) {
        this.laws = docs.stream().filter(Documents.Doc::isLaw).filter(d -> d.lawName() != null).toList();
    }

    public static LawScopeJudge of(Documents documents) {
        return new LawScopeJudge(documents.all());
    }

    /** 이름 하나 → 맞는 법령(없으면 null). */
    public Documents.Doc match(String name) {
        String n = squash(name);
        Documents.Doc best = null;
        for (Documents.Doc d : laws) {
            String k = squash(d.lawName());
            if (n.startsWith(k) && (best == null || k.length() > squash(best.lawName()).length())) {
                best = d;
            }
        }
        return best;
    }

    public List<PreliminaryExit.Reason> judge(Collection<String> lawNames) {
        List<PreliminaryExit.Reason> out = new ArrayList<>();
        for (String name : lawNames) {
            if (name == null || name.isBlank()) {
                continue;
            }
            Documents.Doc d = match(name);
            if (d == null) {
                out.add(new PreliminaryExit.Reason(PreliminaryExit.OUTSIDE_KB_LAW, null, name + ": KB에 없음"));
            } else if (!ANSWER_BASIS.equals(d.usePolicy())) {
                out.add(new PreliminaryExit.Reason(PreliminaryExit.OUTSIDE_KB_LAW, null,
                        name + ": " + d.lawName() + " " + d.usePolicy()));
            }
        }
        return out;
    }

    private static String squash(String s) {
        return WS.matcher(s).replaceAll("");
    }
}
