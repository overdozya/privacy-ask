package io.github.overdozya.privacyask.issues;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.overdozya.privacyask.knowledge.Packages;
import io.github.overdozya.privacyask.knowledge.RuleSet;
import io.github.overdozya.privacyask.rules.Condition;
import io.github.overdozya.privacyask.rules.EvalContext;
import io.github.overdozya.privacyask.rules.FactState;
import io.github.overdozya.privacyask.rules.Grades;
import io.github.overdozya.privacyask.rules.RiskRuleEngine;

/**
 * 쟁점 도출(06 3.3 A3 쟁점): 흐름 하나의 슬롯 → 꾸러미 trigger → 현업 규칙 16 → 쟁점(이름·꾸러미·등급·규칙·조건·해석 쟁점).
 *
 * <ul>
 *   <li>쟁점 = opened_by가 없는 꾸러미(현업 issue_catalog 13)의 trigger가 참인 것. 등급 = 그 쟁점 이름의 켜진 규칙 중
 *       가장 높은 것, 없으면 L(예시 사안 흐름 1 '수집·이용 근거' L). trigger가 거짓인데 규칙만 켜진 쟁점도 넣는다(합집합).</li>
 *   <li>조건 = 꾸러미 conditions 중 when이 참이고 facts(사실 조건)가 모두 참인 것, 꾸러미의 순서대로.</li>
 *   <li>하위 꾸러미(opened_by가 있는 것, v2 PKG14): trigger가 참이면 opened_by의 '꾸러미.조건'이 맞은 쟁점 아래에 단다(결정 8 P-C,
 *       쟁점 이름은 부모 그대로).</li>
 *   <li>해석 쟁점(②, 상태 '해석 쟁점'): 맞은 조건의 interp 중, on_when이 있으면 모두 참일 때만 켠다. 같은 꾸러미에서 맞은 조건의
 *       interp_off에 있으면 끈다(S4 다른 곳 2: off_when 글이 아니라 interp_off를 본다).</li>
 *   <li>2값: trigger·조건·규칙은 참일 때만 참(사실이 모름이거나 슬롯이 미확인이면 거짓, 06 3.2).</li>
 * </ul>
 */
public final class IssueDeriver {
    public static final String INTERP_STATUS = "해석 쟁점";

    /** 하위 꾸러미(PKG14): 부모 쟁점의 어느 조건으로 열렸나, 맞은 조건, 켜진 해석 쟁점. */
    public record SubPackage(String packageId, String parentCondition, List<String> conditionIds,
                             List<String> interpOn) {}

    /** 쟁점 하나(case_judgment_issues 칸과 같은 모양 + 하위 꾸러미). ruleIds는 규칙 세트 순, conditionIds는 꾸러미 순. */
    public record Issue(String issueName, String packageId, String grade, List<String> ruleIds,
                        List<String> conditionIds, List<String> interpOn, List<SubPackage> opens) {
        /** 이 쟁점과 하위 꾸러미에 켜진 해석 쟁점 전부. */
        public List<String> allInterpOn() {
            List<String> out = new ArrayList<>(interpOn);
            opens.forEach(s -> s.interpOn().forEach(i -> out.add(s.packageId() + "." + i)));
            return out;
        }
    }

    /** 흐름 하나의 판정: 슬롯 8개(sort_order 순), 쟁점(꾸러미 순), 켜진 규칙, 흐름 등급(쟁점이 없으면 null). */
    public record FlowIssues(int flowNo, Map<String, SlotResult> slots, List<Issue> issues, List<String> firedRules,
                             String grade) {}

    private final List<Packages.Package> issuePackages;   // opened_by 없음, package_id 순
    private final List<Packages.Package> subPackages;     // opened_by 있음
    private final RiskRuleEngine engine;

    public IssueDeriver(Collection<Packages.Package> packages, RiskRuleEngine engine) {
        this.issuePackages = packages.stream().filter(p -> p.openedBy().isEmpty()).toList();
        this.subPackages = packages.stream().filter(p -> !p.openedBy().isEmpty()).toList();
        this.engine = engine;
    }

    public static IssueDeriver of(Packages packages, RuleSet ruleSet) {
        return new IssueDeriver(packages.current(), RiskRuleEngine.of(ruleSet));
    }

    public List<Packages.Package> issuePackages() {
        return issuePackages;
    }

    public RiskRuleEngine engine() {
        return engine;
    }

    public FlowIssues derive(FlowFacts facts, Map<String, SlotResult> slots) {
        EvalContext ctx = new Ctx(facts, slots);
        List<RuleSet.RiskRule> fired = engine.fired(ctx);
        Map<String, Issue> byName = new LinkedHashMap<>();
        for (Packages.Package p : issuePackages) {
            boolean trig = Condition.holds(p.trigger(), ctx);
            List<RuleSet.RiskRule> rs = fired.stream().filter(r -> r.issueName().equals(p.issueName())).toList();
            if (!trig && rs.isEmpty()) {
                continue;
            }
            List<String> conds = matchedConditions(p, ctx);
            byName.put(p.issueName(), new Issue(p.issueName(), p.packageId(), gradeOf(rs),
                    rs.stream().map(RuleSet.RiskRule::ruleId).toList(), conds, interpOn(p, conds, ctx), List.of()));
        }
        // 꾸러미가 없는 쟁점 이름의 규칙(현업 16은 모두 꾸러미가 있다. 데이터가 바뀌어도 쟁점을 잃지 않게)
        for (RuleSet.RiskRule r : fired) {
            if (!byName.containsKey(r.issueName())) {
                List<RuleSet.RiskRule> rs = fired.stream().filter(x -> x.issueName().equals(r.issueName())).toList();
                byName.put(r.issueName(), new Issue(r.issueName(), null, gradeOf(rs),
                        rs.stream().map(RuleSet.RiskRule::ruleId).toList(), List.of(), List.of(), List.of()));
            }
        }
        for (Packages.Package sub : subPackages) {
            if (!Condition.holds(sub.trigger(), ctx)) {
                continue;
            }
            List<String> conds = matchedConditions(sub, ctx);
            SubPackage sp = null;
            for (String opener : sub.openedBy()) {                 // 'PKG02.C1a'
                int dot = opener.indexOf('.');
                String pid = opener.substring(0, dot), cid = opener.substring(dot + 1);
                for (Map.Entry<String, Issue> e : byName.entrySet()) {
                    Issue is = e.getValue();
                    if (pid.equals(is.packageId()) && is.conditionIds().contains(cid)) {
                        sp = new SubPackage(sub.packageId(), cid, conds, interpOn(sub, conds, ctx));
                        List<SubPackage> opens = new ArrayList<>(is.opens());
                        opens.add(sp);
                        e.setValue(new Issue(is.issueName(), is.packageId(), is.grade(), is.ruleIds(),
                                is.conditionIds(), is.interpOn(), List.copyOf(opens)));
                        break;
                    }
                }
                if (sp != null) {
                    break;
                }
            }
        }
        List<Issue> issues = List.copyOf(byName.values());
        return new FlowIssues(facts.flowNo(), slots, issues, fired.stream().map(RuleSet.RiskRule::ruleId).toList(),
                Grades.max(issues.stream().map(Issue::grade).toList()));
    }

    private static String gradeOf(List<RuleSet.RiskRule> rs) {
        String g = Grades.max(rs.stream().map(RuleSet.RiskRule::grade).toList());
        return g == null ? Grades.L : g;
    }

    /** when이 참이고 facts가 모두 참인 조건 ID, 꾸러미 순. */
    static List<String> matchedConditions(Packages.Package p, EvalContext ctx) {
        List<String> out = new ArrayList<>();
        if (p.conditions() == null || !p.conditions().isArray()) {
            return out;
        }
        for (var c : p.conditions()) {
            if (!Condition.holds(c.get("when"), ctx)) {
                continue;
            }
            boolean ok = true;
            if (c.has("facts")) {
                for (var a : c.get("facts")) {
                    if (!Condition.holds(a, ctx)) {
                        ok = false;
                        break;
                    }
                }
            }
            if (ok) {
                out.add(c.get("id").asString());
            }
        }
        return out;
    }

    /** 맞은 조건들의 해석 쟁점(②) 중 켜진 것(on_when), interp_off로 끈 것은 뺀다. 조건 순. */
    static List<String> interpOn(Packages.Package p, List<String> matched, EvalContext ctx) {
        Set<String> on = new LinkedHashSet<>();
        Set<String> off = new LinkedHashSet<>();
        if (p.conditions() == null || !p.conditions().isArray()) {
            return List.of();
        }
        for (var c : p.conditions()) {
            if (!matched.contains(c.get("id").asString())) {
                continue;
            }
            off.addAll(Condition.strings(c.get("interp_off")));
            if (!c.has("interp")) {
                continue;
            }
            for (var i : c.get("interp")) {
                if (!INTERP_STATUS.equals(i.path("status").asString())) {
                    continue;
                }
                boolean ok = true;
                if (i.has("on_when")) {
                    for (var a : i.get("on_when")) {
                        if (!Condition.holds(a, ctx)) {
                            ok = false;
                            break;
                        }
                    }
                }
                if (ok) {
                    on.add(i.get("id").asString());
                }
            }
        }
        on.removeAll(off);
        return List.copyOf(on);
    }

    /** 쟁점 도출의 조건식 문맥: 사실은 흐름 판, 슬롯은 변환 결과. */
    static final class Ctx implements EvalContext {
        private final FlowFacts facts;
        private final Map<String, SlotResult> slots;

        Ctx(FlowFacts facts, Map<String, SlotResult> slots) {
            this.facts = facts;
            this.slots = slots;
        }

        @Override
        public FactState fact(String factId) {
            return facts.fact(factId);
        }

        @Override
        public SlotView slot(String slotKey) {
            SlotResult r = slots.get(slotKey);
            if (r == null) {
                throw new IllegalStateException("슬롯 " + slotKey + "이 없음");
            }
            return r.view();
        }

        @Override
        public List<Column> ledgerRow(String rowId) {
            return facts.ledgerRow(rowId);
        }
    }
}
