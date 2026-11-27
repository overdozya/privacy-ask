package io.github.overdozya.privacyask.facts;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A1 LLM 출력(prompts/A1_facts.schema.json). flows가 빈 사실 = 공통 사실. 모르는 사실은 내지 않는다(= NOT_FOUND). */
public record FactOutput(List<FlowOut> flows, List<FactOut> facts) {

    public record FlowOut(int flow, String label, String origin, String destination,
                          @JsonProperty("service_name") String serviceName) {}

    public record FactOut(String fact, List<Integer> flows, List<ValueOut> values, String status, String quote) {
        public FactOut {
            flows = flows == null ? List.of() : List.copyOf(flows);
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    public record ValueOut(String label, String detail) {}

    public FactOutput {
        flows = flows == null ? List.of() : List.copyOf(flows);
        facts = facts == null ? List.of() : List.copyOf(facts);
    }
}
