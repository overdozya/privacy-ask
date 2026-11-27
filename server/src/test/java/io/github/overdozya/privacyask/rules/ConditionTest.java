package io.github.overdozya.privacyask.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.overdozya.privacyask.knowledge.RuleSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 조건식 해석기 엔진 테스트: 테스트 안에서 지은 작은 식으로(데이터 없이, 06 3.6). */
class ConditionTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    static JsonNode j(String s) {
        return JSON.readTree(s.replace('\'', '"'));
    }

    /** 사실·슬롯·원장 행을 맵으로 든 문맥. */
    record Ctx(Map<String, FactState> facts, Map<String, SlotView> slots, Map<String, List<Column>> rows)
            implements EvalContext {
        public FactState fact(String id) {
            return facts.getOrDefault(id, FactState.ABSENT);
        }

        public SlotView slot(String k) {
            SlotView v = slots.get(k);
            if (v == null) {
                throw new IllegalStateException(k);
            }
            return v;
        }

        public List<Column> ledgerRow(String id) {
            return rows.get(id);
        }
    }

    static final Ctx CTX = new Ctx(Map.of(
            "A", FactState.confirmed("x"),
            "B", FactState.confirmed("p", "q"),
            "U", FactState.unknown("잘 모르겠음"),
            "L1", new FactState(FactState.MATCHED, List.of("명시 일치"), List.of("R1"), "LEDGER"),
            "L2", FactState.of(FactState.INFERRED, List.of("포괄 일치"))),
            Map.of("s1", new EvalContext.SlotView("CONFIRMED", List.of("위탁", "재위탁")),
                    "s2", new EvalContext.SlotView("UNKNOWN", List.of()),
                    "s3", new EvalContext.SlotView("PARTIAL", List.of("주민등록번호"))),
            Map.of("R1", List.of(new EvalContext.Column("법적 근거", "개인정보 보호법\n제15조 제1항제1호(동의)"))));

    static Tri ev(String s) {
        return Condition.eval(j(s), CTX).value();
    }

    @Test
    void operators() {
        assertThat(ev("{'fact':'A','op':'eq','value':'x'}")).isEqualTo(Tri.TRUE);
        assertThat(ev("{'fact':'B','op':'eq','value':'p'}")).as("eq는 값이 하나일 때만").isEqualTo(Tri.FALSE);
        assertThat(ev("{'fact':'A','op':'in','value':['y','x']}")).isEqualTo(Tri.TRUE);
        assertThat(ev("{'fact':'B','op':'in','value':['p','q']}")).isEqualTo(Tri.FALSE);
        assertThat(ev("{'fact':'B','op':'contains','value':'q'}")).isEqualTo(Tri.TRUE);
        assertThat(ev("{'fact':'B','op':'contains_any','value':['z','p']}")).isEqualTo(Tri.TRUE);
        assertThat(ev("{'fact':'B','op':'contains_any','value':['z']}")).isEqualTo(Tri.FALSE);
        assertThatThrownBy(() -> ev("{'fact':'A','op':'gt','value':1}")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownAndOpenFactsAreUnknown() {
        Condition.Result u = Condition.eval(j("{'fact':'U','op':'eq','value':'x'}"), CTX);
        assertThat(u.value()).isEqualTo(Tri.UNKNOWN);
        assertThat(u.unknownFacts()).containsExactly("U");
        Condition.Result n = Condition.eval(j("{'fact':'N','op':'eq','value':'x'}"), CTX);
        assertThat(n.value()).isEqualTo(Tri.UNKNOWN);
        assertThat(n.unknownFacts()).containsExactly("N");
        // 상태 원자는 늘 참/거짓
        assertThat(ev("{'fact':'N','op':'status','value':'NOT_FOUND'}")).isEqualTo(Tri.TRUE);
        assertThat(ev("{'fact':'U','op':'status','value':['UNKNOWN','MATCHED']}")).isEqualTo(Tri.TRUE);
    }

    @Test
    void kleeneLogic() {
        String t = "{'fact':'A','op':'eq','value':'x'}", f = "{'fact':'A','op':'eq','value':'y'}",
                u = "{'fact':'U','op':'eq','value':'x'}", n = "{'fact':'N','op':'eq','value':'x'}";
        assertThat(ev("{'all':[" + t + "," + u + "]}")).isEqualTo(Tri.UNKNOWN);
        assertThat(ev("{'all':[" + f + "," + u + "]}")).isEqualTo(Tri.FALSE);
        assertThat(ev("{'any':[" + t + "," + u + "]}")).isEqualTo(Tri.TRUE);
        assertThat(ev("{'any':[" + f + "," + u + "]}")).isEqualTo(Tri.UNKNOWN);
        assertThat(ev("{'not':" + u + "}")).isEqualTo(Tri.UNKNOWN);
        assertThat(ev("{'not':" + f + "}")).isEqualTo(Tri.TRUE);
        assertThat(ev("{'all':[]}")).isEqualTo(Tri.TRUE);
        assertThat(ev("{'any':[]}")).isEqualTo(Tri.FALSE);
        assertThat(Condition.eval(j("{'all':[" + t + "," + u + "," + n + "]}"), CTX).unknownFacts()).containsExactly("U", "N");
    }

    @Test
    void slotAtomsAreTwoValued() {
        assertThat(ev("{'slot':'s1','op':'contains_any','value':['재위탁']}")).isEqualTo(Tri.TRUE);
        assertThat(ev("{'slot':'s2','op':'eq','value':'있음'}")).as("미확인 슬롯은 거짓").isEqualTo(Tri.FALSE);
        assertThat(ev("{'not':{'slot':'s2','op':'eq','value':'있음'}}")).isEqualTo(Tri.TRUE);
        assertThat(ev("{'slot':'s3','op':'contains','value':'주민등록번호'}")).as("일부 미확인은 아는 값만").isEqualTo(Tri.TRUE);
        assertThatThrownBy(() -> ev("{'slot':'none','op':'eq','value':'x'}")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ledgerAtom() {
        assertThat(ev("{'ledger':'L1','column':'법적근거','op':'contains','value':'제1항 제1호(동의)'}"))
                .as("칸 이름·글·값 모두 공백 뺌").isEqualTo(Tri.TRUE);
        assertThat(ev("{'ledger':'L1','column':'법적 근거','op':'contains_any','value':['제24조']}")).isEqualTo(Tri.FALSE);
        assertThat(ev("{'ledger':'L2','column':'법적 근거','op':'contains','value':'제1항'}")).as("MATCHED 아님").isEqualTo(Tri.FALSE);
        assertThat(ev("{'ledger':'L3','column':'법적 근거','op':'contains','value':'제1항'}")).isEqualTo(Tri.UNKNOWN);
    }

    @Test
    void supportFacts() {
        // all 참: 모든 자식, any 참: 참인 자식, 판이 없는 사실·슬롯 원자는 뺌
        JsonNode n = j("{'all':[{'fact':'A','op':'eq','value':'x'},{'any':[{'fact':'B','op':'contains','value':'q'},"
                + "{'fact':'U','op':'eq','value':'x'}]},{'not':{'fact':'N','op':'status','value':'MATCHED'}},"
                + "{'slot':'s1','op':'contains','value':'위탁'}]}");
        assertThat(Condition.eval(n, CTX).value()).isEqualTo(Tri.TRUE);
        assertThat(Condition.support(n, CTX, true)).containsExactly("A", "B");
        // all 거짓: 거짓인 자식만
        JsonNode m = j("{'all':[{'fact':'A','op':'eq','value':'y'},{'fact':'B','op':'contains','value':'q'}]}");
        assertThat(Condition.support(m, CTX, false)).containsExactly("A");
        assertThat(Condition.refFacts(n)).containsExactly("A", "B", "U", "N");
        assertThat(Condition.refSlots(n)).containsExactly("s1");
    }

    @Test
    void riskRulesExitPolicyGrades() {
        RuleSet.RiskRule hi = new RuleSet.RiskRule("R1", "n", "a", j("{'all':[{'slot':'s1','op':'contains','value':'위탁'}]}"),
                "H", "쟁점", List.of(), "", true);
        RuleSet.RiskRule lo = new RuleSet.RiskRule("R2", "n", "a", j("{'all':[{'slot':'s2','op':'eq','value':'x'}]}"),
                "M", "쟁점", List.of(), "", true);
        assertThat(new RiskRuleEngine(List.of(hi, lo)).fired(CTX)).containsExactly(hi);
        assertThat(Grades.max(List.of("L", "H", "M"))).isEqualTo("H");
        assertThat(Grades.max(List.of())).isNull();
        ExitPolicy p = new ExitPolicy(Map.of("H", "TRANSFER", "M", "TRANSFER", "L", "ANSWER"));
        assertThat(p.exit("L", List.of())).isEqualTo("ANSWER");
        assertThat(p.exit("L", List.of("사유"))).isEqualTo("TRANSFER");
        assertThat(p.exit("M", List.of())).isEqualTo("TRANSFER");
        assertThat(p.exit(null, List.of())).as("쟁점 없음").isEqualTo("TRANSFER");
    }
}
