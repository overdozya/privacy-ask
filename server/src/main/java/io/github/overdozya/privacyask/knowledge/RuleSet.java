package io.github.overdozya.privacyask.knowledge;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;

/** 현업 규칙 세트(rule_sets 1행 + risk_rules 16, 06 2.4). exitPolicy: 사안 등급 H·M·L → 출구. */
public record RuleSet(String version, String status, String changeReason, Map<String, String> exitPolicy,
                      String sha256, List<RiskRule> rules) {

    public record RiskRule(String ruleId, String name, String axis, JsonNode condition, String grade,
                           String issueName, List<String> legalRefs, String description, boolean active) {}

    public Optional<RiskRule> rule(String ruleId) {
        return rules.stream().filter(r -> r.ruleId().equals(ruleId)).findFirst();
    }

    public List<RiskRule> activeRules() {
        return rules.stream().filter(RiskRule::active).toList();
    }

    public Optional<String> exitFor(String grade) {
        return Optional.ofNullable(exitPolicy.get(grade));
    }
}
