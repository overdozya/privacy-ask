package io.github.overdozya.privacyask.intake;

import java.util.List;

/**
 * A0 LLM 출력(prompts/A0_intake.schema.json). kind: OWN_CASE (가) 자기 사안, ACTION (나) 처리 행위,
 * INCIDENT (다) 사고, RIGHTS_REQUEST (라) 실제 권리 요청, LAW_AREA (마) 법령·영역 이름(name). 04 4.3.
 */
public record IntakeSignals(List<Signal> signals) {
    public static final String OWN_CASE = "OWN_CASE", ACTION = "ACTION", INCIDENT = "INCIDENT",
            RIGHTS_REQUEST = "RIGHTS_REQUEST", LAW_AREA = "LAW_AREA";

    public record Signal(String kind, String quote, String name) {
        public static Signal of(String kind, String quote) {
            return new Signal(kind, quote, null);
        }
    }

    public IntakeSignals {
        signals = signals == null ? List.of() : List.copyOf(signals);
    }

    public boolean has(String kind) {
        return signals.stream().anyMatch(s -> kind.equals(s.kind()));
    }
}
