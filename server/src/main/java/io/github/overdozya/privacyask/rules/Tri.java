package io.github.overdozya.privacyask.rules;

/** 3값(클리니) 논리의 값: 참 · 거짓 · 모름(S3 슬롯변환_v1 '의미' 결합). */
public enum Tri {
    TRUE, FALSE, UNKNOWN;

    public static Tri of(boolean b) {
        return b ? TRUE : FALSE;
    }

    public Tri not() {
        return switch (this) {
            case TRUE -> FALSE;
            case FALSE -> TRUE;
            case UNKNOWN -> UNKNOWN;
        };
    }
}
