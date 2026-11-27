package io.github.overdozya.privacyask.issues;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.overdozya.privacyask.knowledge.SlotRules;
import io.github.overdozya.privacyask.rules.FactState;
import tools.jackson.databind.json.JsonMapper;

/**
 * SlotConverter 엔진 테스트: 테스트 안에서 지은 작은 규칙으로(데이터 없이, 06 3.6). 실제 규칙은 SlotRulesVectorTest.
 * 단일 슬롯 'one'(사실 X·Y), 복수 슬롯 'many'(사실 M), 'dep'는 'one'을 본다(계산 순서).
 */
class SlotConverterEngineTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    static SlotRules.Rule r(String slot, int ord, String when, String effect, List<String> values, String flag) {
        return new SlotRules.Rule(slot, ord, JSON.readTree(when.replace('\'', '"')), effect, values, flag, List.of(), "");
    }

    static final SlotConverter C = new SlotConverter(List.of(
            new SlotConverter.SlotDef("dep", false, 30, List.of(
                    r("dep", 1, "{'not':{'slot':'one','op':'eq','value':'높음'}}", "SKIP", List.of(), null),
                    r("dep", 2, "{'all':[]}", "SET", List.of("예"), null))),
            new SlotConverter.SlotDef("one", false, 10, List.of(
                    r("one", 1, "{'fact':'X','op':'eq','value':'a'}", "SET", List.of("높음"), null),
                    r("one", 2, "{'fact':'Y','op':'eq','value':'b'}", "SET", List.of("낮음"), "CHECK_ITEM"))),
            new SlotConverter.SlotDef("many", true, 20, List.of(
                    r("many", 1, "{'fact':'M','op':'contains','value':'p'}", "ADD", List.of("P"), null),
                    r("many", 2, "{'fact':'M','op':'contains','value':'q'}", "ADD", List.of("Q"), "FACT_CONFLICT"),
                    r("many", 3, "{'fact':'Z','op':'eq','value':'z'}", "ADD", List.of("Z"), null)))),
            List.of(new SlotConverter.FactDef("X", 1, Set.of("one")), new SlotConverter.FactDef("Y", 2, Set.of("one")),
                    new SlotConverter.FactDef("M", 3, Set.of("many")), new SlotConverter.FactDef("Z", 4, Set.of("many"))));

    static Map<String, SlotResult> conv(Map<String, FactState> facts) {
        return C.convert(new FlowFacts(1, facts, id -> null));
    }

    @Test
    void orderFollowsSlotAtoms() {
        assertThat(C.order()).containsExactly("one", "many", "dep");
        assertThat(C.slotKeys()).containsExactly("one", "many", "dep");
    }

    @Test
    void singleFirstTrueRule() {
        SlotResult s = conv(Map.of("X", FactState.confirmed("a"), "Y", FactState.confirmed("b"))).get("one");
        assertThat(s.status()).isEqualTo("CONFIRMED");
        assertThat(s.values()).containsExactly("높음");
        assertThat(s.fired()).containsExactly(1);
        assertThat(s.flags()).isEmpty();
        assertThat(s.basisFacts()).as("정한 규칙까지 본 규칙의 사실만 근거").containsExactly("X");
    }

    @Test
    void singleOpenEarlierRuleGoesOnButUnknownStops() {
        // 앞 규칙이 열림(NOT_FOUND)이면 뒤 규칙으로 정하고 열림에 남김
        SlotResult open = conv(Map.of("Y", FactState.confirmed("b"))).get("one");
        assertThat(open.status()).isEqualTo("CONFIRMED");
        assertThat(open.values()).containsExactly("낮음");
        assertThat(open.flags()).containsExactly("CHECK_ITEM");
        assertThat(open.openFacts()).containsExactly("X");
        // 앞 규칙이 미확인이면 UNKNOWN(결정 5)
        SlotResult unk = conv(Map.of("X", FactState.unknown("잘 모르겠음"), "Y", FactState.confirmed("b"))).get("one");
        assertThat(unk.status()).isEqualTo("UNKNOWN");
        assertThat(unk.values()).isEmpty();
        assertThat(unk.unknownFacts()).containsExactly("X");
        // 아무것도 모름 → OPEN
        assertThat(conv(Map.of()).get("one").status()).isEqualTo("OPEN");
    }

    @Test
    void multiAddsAndPartial() {
        SlotResult both = conv(Map.of("M", FactState.confirmed("p", "q"), "Z", FactState.confirmed("y"))).get("many");
        assertThat(both.status()).isEqualTo("CONFIRMED");
        assertThat(both.values()).containsExactly("P", "Q");
        assertThat(both.flags()).containsExactly("FACT_CONFLICT");
        assertThat(both.fired()).containsExactly(1, 2);
        // 결정 2: 아는 값은 넣고 모르는 값만 미확인 → PARTIAL
        SlotResult part = conv(Map.of("M", FactState.confirmed("p"), "Z", FactState.unknown("잘 모르겠음"))).get("many");
        assertThat(part.status()).isEqualTo("PARTIAL");
        assertThat(part.values()).containsExactly("P");
        assertThat(part.unknownFacts()).containsExactly("Z");
        assertThat(conv(Map.of("Z", FactState.unknown("잘 모르겠음"))).get("many").status()).isEqualTo("UNKNOWN");
        assertThat(conv(Map.of("M", FactState.confirmed("p"))).get("many").openFacts()).containsExactly("Z");
    }

    @Test
    void skipByEarlierSlotAndForced() {
        assertThat(conv(Map.of()).get("dep").status()).as("one이 OPEN이면 슬롯 원자는 거짓 → not 참 → SKIP").isEqualTo("SKIPPED");
        assertThat(conv(Map.of("X", FactState.confirmed("a"))).get("dep").values()).containsExactly("예");
        Map<String, SlotResult> forced = C.convert(new FlowFacts(1, Map.of(), id -> null),
                Map.of("one", SlotResult.forced("CONFIRMED", List.of("높음"))));
        assertThat(forced.get("one").values()).containsExactly("높음");
        assertThat(forced.get("dep").values()).as("뒤 슬롯은 가정한 값을 본다").containsExactly("예");
    }
}
