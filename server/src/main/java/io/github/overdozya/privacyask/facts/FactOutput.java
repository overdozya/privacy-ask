package io.github.overdozya.privacyask.facts;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A1 LLM 출력(prompts/A1_facts.schema.json). flows가 빈 사실 = 공통 사실. 모르는 사실은 내지 않는다(= NOT_FOUND). */
public record FactOutput(List<FlowOut> flows, List<FactOut> facts, List<String> unanswered) {
    /** 자유 답에서 받은 질문의 사실을 답이 말하지 않았을 때 그 사실 ID(S7b: 받은 질문의 사실은 facts나 여기 둘 중 하나에 꼭). */
    public FactOutput(List<FlowOut> flows, List<FactOut> facts) {
        this(flows, facts, List.of());
    }


    /** counterpartyCloudIt: 받는·맡는 상대가 클라우드·서버·IT 운영사인가(true/false, null = 글로 모름). A2 F-T5 후보를 가른다. */
    public record FlowOut(int flow, String label, String origin, String destination,
                          @JsonProperty("service_name") String serviceName,
                          @JsonProperty("counterparty_cloud_it") Boolean counterpartyCloudIt) {}

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
        unanswered = unanswered == null ? List.of() : List.copyOf(unanswered);
    }
}
