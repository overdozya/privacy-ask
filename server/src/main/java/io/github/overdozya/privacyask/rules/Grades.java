package io.github.overdozya.privacyask.rules;

import java.util.Collection;
import java.util.List;

/** 위험 등급 H · M · L(현업 규칙 세트). 높은 쪽이 이긴다(04 결정 4). */
public final class Grades {
    public static final String H = "H", M = "M", L = "L";
    private static final List<String> ORDER = List.of(L, M, H);

    private Grades() {}

    public static int rank(String grade) {
        int i = ORDER.indexOf(grade);
        if (i < 0) {
            throw new IllegalArgumentException("모르는 등급: " + grade);
        }
        return i;
    }

    /** 가장 높은 등급, 비면 null. */
    public static String max(Collection<String> grades) {
        String best = null;
        for (String g : grades) {
            if (best == null || rank(g) > rank(best)) {
                best = g;
            }
        }
        return best;
    }
}
