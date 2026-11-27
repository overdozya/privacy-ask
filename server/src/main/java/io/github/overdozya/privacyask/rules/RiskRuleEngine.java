package io.github.overdozya.privacyask.rules;

import java.util.List;

import io.github.overdozya.privacyask.knowledge.RuleSet;

/**
 * 현업 규칙 세트의 위험 규칙(06 3.3 A3 쟁점). 켜진 규칙 중 조건이 참인 것을 규칙 세트 순서로 낸다.
 * 현업 규칙은 슬롯 원자와 all뿐이라 2값으로 본다(슬롯이 미확인이면 그 원자는 거짓).
 */
public final class RiskRuleEngine {
    private final List<RuleSet.RiskRule> rules;

    public RiskRuleEngine(List<RuleSet.RiskRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public static RiskRuleEngine of(RuleSet ruleSet) {
        return new RiskRuleEngine(ruleSet.activeRules());
    }

    public List<RuleSet.RiskRule> rules() {
        return rules;
    }

    public List<RuleSet.RiskRule> fired(EvalContext ctx) {
        return rules.stream().filter(r -> Condition.holds(r.condition(), ctx)).toList();
    }
}
