package io.github.overdozya.privacyask.issues;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.overdozya.privacyask.knowledge.KnowledgeSnapshot;
import io.github.overdozya.privacyask.knowledge.LoadedDb;
import io.github.overdozya.privacyask.knowledge.Packages;
import io.github.overdozya.privacyask.knowledge.RuleSet;
import io.github.overdozya.privacyask.rules.EvalContext;
import io.github.overdozya.privacyask.rules.FactState;
import tools.jackson.databind.JsonNode;

/**
 * 06 7절 S5 ②: 현업 규칙 16 각 1건, 쟁점 trigger 14(꾸러미 v2) 각 1건. 조건식에서 그 식을 참으로 만드는 슬롯·사실을 지어
 * (eq·contains → 그 값, in·contains_any → 첫 값, any → 첫 가지) 규칙이 켜지고 쟁점이 서는지 본다. 규칙 데이터는 적재 DB.
 * 결정 5(legal_basis 여러 개가 맞을 때)도 실제 변환 규칙으로 본다.
 */
class RulesAndTriggersTest {
    static KnowledgeSnapshot snap;
    static CaseJudge judge;

    @BeforeAll
    static void load() {
        assumeThat(LoadedDb.available()).as("적재 파일 또는 Docker 없음").isTrue();
        snap = LoadedDb.snapshot();
        judge = CaseJudge.of(snap);
    }

    /** 식을 참으로 만드는 슬롯 값·사실. */
    record Made(Map<String, List<String>> slots, Map<String, FactState> facts) {
        Made() {
            this(new LinkedHashMap<>(), new LinkedHashMap<>());
        }
    }

    static void make(JsonNode n, Made m) {
        if (n.has("all")) {
            n.get("all").forEach(c -> make(c, m));
        } else if (n.has("any")) {
            make(n.get("any").get(0), m);
        } else if (n.has("not")) {
            throw new IllegalStateException("not은 짓지 않음: " + n);
        } else {
            String op = n.get("op").asString();
            JsonNode v = n.get("value");
            String val = op.equals("in") || op.equals("contains_any") ? v.get(0).asString() : v.asString();
            if (n.has("slot")) {
                m.slots().computeIfAbsent(n.get("slot").asString(), k -> new ArrayList<>()).add(val);
            } else {
                m.facts().put(n.get("fact").asString(), FactState.confirmed(val));
            }
        }
    }

    static IssueDeriver.FlowIssues derive(Made m) {
        Map<String, SlotResult> slots = new LinkedHashMap<>();
        for (String sk : judge.converter().slotKeys()) {
            List<String> vs = m.slots().get(sk);
            slots.put(sk, vs == null ? SlotResult.forced(SlotResult.OPEN, List.of())
                    : SlotResult.forced(SlotResult.CONFIRMED, vs.stream().distinct().toList()));
        }
        FlowFacts f = new FlowFacts(1, m.facts(), id -> List.<EvalContext.Column>of());
        return judge.deriver().derive(f, slots);
    }

    static Stream<Arguments> riskRules() {
        assumeThat(LoadedDb.available()).isTrue();
        return LoadedDb.snapshot().ruleSet().activeRules().stream().map(r -> Arguments.of(r.ruleId(), r));
    }

    static Stream<Arguments> packages() {
        assumeThat(LoadedDb.available()).isTrue();
        return LoadedDb.snapshot().packages().current().stream().map(p -> Arguments.of(p.packageId(), p));
    }

    @Test
    void sixteenRulesFourteenTriggers() {
        assertThat(snap.ruleSet().activeRules()).hasSize(16);
        assertThat(snap.packages().current()).hasSize(14);
        assertThat(judge.deriver().issuePackages()).hasSize(13);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("riskRules")
    void eachRiskRuleFires(String id, RuleSet.RiskRule rule) {
        Made m = new Made();
        make(rule.condition(), m);
        IssueDeriver.FlowIssues fi = derive(m);
        assertThat(fi.firedRules()).contains(id);
        IssueDeriver.Issue is = fi.issues().stream().filter(i -> i.issueName().equals(rule.issueName())).findFirst()
                .orElseThrow(() -> new AssertionError(id + " 쟁점 없음: " + rule.issueName()));
        assertThat(is.ruleIds()).contains(id);
        assertThat(is.packageId()).as("규칙의 쟁점에 꾸러미가 있음").isNotNull();
        // 등급 = 그 쟁점에 켜진 규칙 중 가장 높은 것(자기 등급 이상)
        assertThat(io.github.overdozya.privacyask.rules.Grades.rank(is.grade()))
                .isGreaterThanOrEqualTo(io.github.overdozya.privacyask.rules.Grades.rank(rule.grade()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("packages")
    void eachTriggerOpens(String id, Packages.Package p) {
        Made m = new Made();
        make(p.trigger(), m);
        IssueDeriver.FlowIssues fi = derive(m);
        if (p.openedBy().isEmpty()) {
            IssueDeriver.Issue is = fi.issues().stream().filter(i -> id.equals(i.packageId())).findFirst()
                    .orElseThrow(() -> new AssertionError(id + " 쟁점이 서지 않음"));
            assertThat(is.issueName()).isEqualTo(p.issueName());
            assertThat(is.conditionIds()).isNotEmpty();
        } else {
            // PKG14(결정 8 P-C): 쟁점 이름은 부모(목적 외 이용) 그대로, 그 아래 하위 꾸러미로 열림 + 해석 쟁점 I29
            IssueDeriver.Issue parent = fi.issues().stream().filter(i -> !i.opens().isEmpty()).findFirst()
                    .orElseThrow(() -> new AssertionError(id + " 하위 꾸러미로 열리지 않음"));
            IssueDeriver.SubPackage sp = parent.opens().get(0);
            assertThat(sp.packageId()).isEqualTo(id);
            assertThat(p.openedBy()).contains(parent.packageId() + "." + sp.parentCondition());
            assertThat(fi.issues()).extracting(IssueDeriver.Issue::issueName).doesNotContain(p.issueName());
        }
    }

    @Test
    void nothingKnownNoIssue() {
        IssueDeriver.FlowIssues fi = derive(new Made());
        assertThat(fi.issues()).isEmpty();
        assertThat(fi.firedRules()).isEmpty();
        assertThat(fi.grade()).isNull();
    }

    /** PKG14: 목적 외 + 이어지는 새 용도 + L4 없음 → PKG02 C1a 아래, I29 켬. L4 명시 일치 → PKG01 C7 아래, I29 끔(결정 9). */
    @Test
    void pkg14InterpOnAndOff() {
        Made out = new Made();
        out.slots().put("purpose_scope", List.of("목적 외"));
        out.facts().put("F-P1", FactState.confirmed("같은 서비스·거래에서 이어지는 새 용도"));
        out.facts().put("L4", FactState.of(FactState.INFERRED, List.of("없음")));
        IssueDeriver.Issue pkg02 = derive(out).issues().stream().filter(i -> "PKG02".equals(i.packageId())).findFirst().orElseThrow();
        assertThat(pkg02.conditionIds()).containsExactly("C1", "C1a");
        assertThat(pkg02.opens()).containsExactly(new IssueDeriver.SubPackage("PKG14", "C1a", List.of("C1"), List.of("I29")));
        assertThat(pkg02.allInterpOn()).containsExactly("PKG14.I29");

        Made in = new Made();
        in.slots().put("purpose_scope", List.of("수집 목적 내"));
        in.slots().put("legal_basis", List.of("동의(기존 범위 내)"));
        in.facts().put("F-P1", FactState.confirmed("같은 서비스·거래에서 이어지는 새 용도"));
        in.facts().put("L4", FactState.of(FactState.MATCHED, List.of("명시 일치")));
        IssueDeriver.FlowIssues fi = derive(in);
        IssueDeriver.Issue pkg01 = fi.issues().stream().filter(i -> "PKG01".equals(i.packageId())).findFirst().orElseThrow();
        assertThat(pkg01.conditionIds()).containsExactly("C1", "C7");
        assertThat(pkg01.opens()).containsExactly(new IssueDeriver.SubPackage("PKG14", "C7", List.of("C2"), List.of()));
        assertThat(PreliminaryExit.reasonsOf(fi)).isEmpty();
        assertThat(judge.combine(List.of(fi), List.of()).exit().exitType()).isEqualTo("ANSWER");
    }

    /** I04(PKG01 C5 정당한 이익)는 늘 켬, I26(PKG13 C1)은 on_when(F-AD5 하나로 받음)일 때만. */
    @Test
    void interpIssuesI04AndI26() {
        Made lb = new Made();
        lb.slots().put("legal_basis", List.of("정당한 이익"));
        IssueDeriver.FlowIssues a = derive(lb);
        assertThat(a.issues().get(0).interpOn()).containsExactly("I04");
        assertThat(judge.combine(List.of(a), List.of()).exit())
                .satisfies(e -> assertThat(e.exitType()).isEqualTo("TRANSFER"))
                .satisfies(e -> assertThat(e.reasons()).extracting(PreliminaryExit.Reason::code).containsExactly("INTERP_ISSUE"));

        Made ad = new Made();
        ad.slots().put("ad_transmission", List.of("있음(수신동의 확보)"));
        assertThat(derive(ad).issues().get(0).interpOn()).isEmpty();
        ad.facts().put("F-AD5", FactState.confirmed("하나로 받음"));
        assertThat(derive(ad).issues().get(0).interpOn()).containsExactly("I26");
    }

    // ───────── 결정 5 ─────────

    static FlowFacts flow(Map<String, FactState> facts, String ledgerBasis) {
        List<EvalContext.Column> row = List.of(new EvalContext.Column("법적 근거", ledgerBasis));
        return new FlowFacts(1, facts, id -> "R1".equals(id) ? row : null);
    }

    static FactState matched() {
        return new FactState(FactState.MATCHED, List.of("명시 일치"), List.of("R1"), "LEDGER");
    }

    /** 결정 5: 원장 명시 일치 값이 먼저, 없으면 등급이 높은 값, 앞 규칙이 미확인이면 UNKNOWN. */
    @Test
    void decision5LegalBasis() {
        SlotConverter c = judge.converter();
        // 원장 명시 일치(제1항제1호 동의)가 사실 답(법령)보다 먼저
        Map<String, FactState> f1 = new LinkedHashMap<>();
        f1.put("L1", matched());
        f1.put("F-B2", FactState.confirmed("예(법령·조문)"));
        assertThat(c.convert(flow(f1, "개인정보 보호법 제15조 제1항제1호(동의)")).get("legal_basis").values())
                .containsExactly("동의(기존 범위 내)");
        // 원장 없음: 신규 동의(중)와 법령(저)이 둘 다 맞으면 높은 등급 쪽
        Map<String, FactState> f2 = new LinkedHashMap<>();
        f2.put("L1", FactState.of(FactState.INFERRED, List.of("없음")));
        f2.put("F-B3", FactState.confirmed("앞으로 받을 예정"));
        f2.put("F-B2", FactState.confirmed("예(법령·조문)"));
        SlotResult r2 = c.convert(flow(f2, "")).get("legal_basis");
        assertThat(r2.values()).containsExactly("동의(신규 필요)");
        assertThat(judge.judgeFlow(flow(f2, "")).issues().get(0).grade()).isEqualTo("M");
        // 앞 규칙(동의 쪽)이 현업 미확인이면 뒤의 법령으로 정하지 않고 UNKNOWN
        Map<String, FactState> f3 = new LinkedHashMap<>(f2);
        f3.put("F-B3", FactState.unknown("잘 모르겠음"));
        SlotResult r3 = c.convert(flow(f3, "")).get("legal_basis");
        assertThat(r3.status()).isEqualTo(SlotResult.UNKNOWN);
        assertThat(r3.unknownFacts()).containsExactly("F-B3");
        // 열림(아직 안 물음)이면 뒤 규칙으로 정하고 열림에 남김
        Map<String, FactState> f4 = new LinkedHashMap<>(f2);
        f4.remove("F-B3");
        SlotResult r4 = c.convert(flow(f4, "")).get("legal_basis");
        assertThat(r4.status()).isEqualTo(SlotResult.CONFIRMED);
        assertThat(r4.values()).containsExactly("법령");
        assertThat(r4.openFacts()).contains("F-B3");
    }
}
