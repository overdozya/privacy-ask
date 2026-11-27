package io.github.overdozya.privacyask.answer;

import java.util.List;

/** A5 일반 질의 LLM 출력(prompts/A5_general.schema.json, G2): 안B 목록에서 고른 조 키와 이유. */
public record GeneralPicks(List<Pick> picks, boolean noBasis, String reason) {

    public record Pick(String key, String why) {}

    public GeneralPicks {
        picks = picks == null ? List.of() : List.copyOf(picks);
    }
}
