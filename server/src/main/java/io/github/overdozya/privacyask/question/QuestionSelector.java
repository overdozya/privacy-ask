package io.github.overdozya.privacyask.question;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.github.overdozya.privacyask.issues.CaseJudge;
import io.github.overdozya.privacyask.issues.FlowFacts;
import io.github.overdozya.privacyask.issues.IssueDeriver;
import io.github.overdozya.privacyask.issues.PreliminaryExit;
import io.github.overdozya.privacyask.issues.SlotResult;
import io.github.overdozya.privacyask.knowledge.Catalog;
import io.github.overdozya.privacyask.knowledge.SlotRules;
import io.github.overdozya.privacyask.record.CaseState;
import io.github.overdozya.privacyask.rules.FactState;

/**
 * A2 다음 질문 고르기(06 3.5): "답에 따라 쟁점·등급·출구가 바뀌는 사실만" 하나 묻는다. 메모리 계산뿐(DB·LLM 없음).
 *
 * <p>후보(04 1.0·1.9, 메인 결정 2026-11-27):
 * <ul>
 *   <li>쓰임이 '슬롯 값'·'출구(해석 표시)'인 F 사실. L 사실(코드가 원장에서 찾음), '꾸러미 조건'·'검토 묶음'은 묻지 않는다.
 *       공통 사실 F-S1(서비스명)은 판이 없으면 가장 먼저 묻는다(06 3.3 A3 기준선 "서비스명이 없으면 F-S1을 먼저").</li>
 *   <li>지금 판이 NOT_FOUND(안 물음)·UNKNOWN('잘 모르겠음', 다시 묻기 1회 = 04 1.9)이거나, 원장 출처 INFERRED(포괄 일치)이고,
 *       그 (흐름, 사실)을 물은 횟수가 2 미만. 묻는 차례 = 물은 횟수 + 1.</li>
 *   <li>F-T5는 A1이 상대를 클라우드·IT 운영사로 낸 흐름(cloudFlows)에서만.</li>
 *   <li>L 사실이 MATCHED인 흐름의 짝 F 사실(L1 ↔ F-B1~F-B5·F-P1, L2 ↔ F-T1·F-T2)은 뺀다. 단 그 F 사실의 슬롯이 그 흐름에서
 *       아직 OPEN이면(원장이 슬롯을 못 정함, S3 벡터 B-04 '제23조만') 빼지 않는다.</li>
 * </ul>
 *
 * <p>계산(물을 가치): (가) 후보의 보기마다(단일: 보기 하나씩, '잘 모르겠음'은 미확인 판. 복수: 보기 하나씩 + 미확인) 그 흐름을 다시
 * 판정해 (그 흐름의 쟁점 = 이름·꾸러미·등급·규칙·조건·해석 쟁점, 사안 등급, 예비 출구)가 하나라도 다르면 묻는다. 다른 사실로 값이
 * 이미 정해지면 결과가 같아 저절로 빠진다(04 1.9 끝 줄, 결정 6·7). (나) 보탬: (가)로는 안 나오지만, 후보가 열림에 남은 슬롯이 OPEN이고,
 * 그 슬롯 값에 따라 결과가 바뀌며(슬롯 값 가정), 이 후보의 보기('잘 모르겠음' 뺌)에 따라 그 슬롯의 상태·값·열림이 달라지면 묻는다.
 * 두 사실이 함께 정해야 하는 슬롯(F-M1·F-M2, F-A1~F-A4)이 하나씩 보면 늘 OPEN이라 끝까지 안 묻히는 것을 막는다.
 *
 * <p>순서: 공통 사실 → 흐름 번호 → 사실 순서. 사실 순서는 Order.CATALOG(06 3.5: fact_catalog.ord, 기본)이거나
 * Order.RULE(제안: 슬롯 계산 순서 → 그 사실이 처음 나오는 변환 규칙 ord → 규칙 안 원자 순서 → ord. 앞 규칙이 등급이 높은 값이라
 * (결정 5) 앞 규칙을 정하는 사실부터 물으면 뒤 규칙의 사실이 저절로 빠진다. 결과 메모 참조). 하나만 묻는다.
 */
public final class QuestionSelector {
    public static final Set<String> ASKED_USAGES = Set.of("슬롯 값", "출구(해석 표시)");
    public static final String SERVICE_FACT = "F-S1", CLOUD_FACT = "F-T5";
    public static final int MAX_ASKS = 2;
    /** L 사실 → MATCHED면 묻지 않는 짝 F 사실(04 1.0 "명시 일치면 그 사실은 묻지 않는다"). */
    public static final Map<String, List<String>> LEDGER_PAIRS = Map.of(
            "L1", List.of("F-B1", "F-B2", "F-B3", "F-B4", "F-B5", "F-P1"),
            "L2", List.of("F-T1", "F-T2"));

    /** (흐름, 사실). 공통 사실은 flowNo null. */
    public record Key(Integer flowNo, String factId) {
        @Override
        public String toString() {
            return (flowNo == null ? "공통" : flowNo.toString()) + ":" + factId;
        }
    }

    /** 고른 질문. why: 왜 물을 가치가 있나(단계 기록 output에 그대로). impact: 보기에 따라 무엇이 바뀌나(S7b). */
    public record Ask(Integer flowNo, String factId, int askNo, String why, Impact impact, boolean raised) {
        public Ask(Integer flowNo, String factId, int askNo, String why) {
            this(flowNo, factId, askNo, why, Impact.DETAIL, false);
        }

        public Ask(Integer flowNo, String factId, int askNo, String why, Impact impact) {
            this(flowNo, factId, askNo, why, impact, false);
        }
    }

    /**
     * 보기에 따라 바뀌는 것(S7b, 큰 것부터): EXIT = 사안 등급·출구, ISSUE = 그 흐름의 쟁점 이름, GRADE = 쟁점 등급,
     * DETAIL = 쟁점 안의 조건·규칙·해석 쟁점·하위 꾸러미, 또는 함께 정하는 슬롯(06과 다르게 한 곳 1). 서비스명(F-S1)은 EXIT로 둔다.
     */
    public enum Impact { EXIT, ISSUE, GRADE, DETAIL }

    /**
     * 후보 순서(S7b): ORDER = 공통 → 흐름 → 사실 순서 그대로, IMPACT = Impact가 큰 것부터, 같으면 문의·답이 이미 꺼낸 주제(raised:
     * 그 사실의 슬롯에 문의·답에서 값이 정해진 다른 사실이 있음)부터, 그다음 ORDER 순.
     */
    public enum Priority { ORDER, IMPACT }

    /**
     * 고른 결과와 계산 기록. worth: 물을 가치가 있는 후보 전부(순서대로, 첫째가 ask). considered: 본 후보 수,
     * evaluations: 흐름 판정 횟수(후보 × 보기 + 슬롯 가정), micros: 계산 시간(06 6.4 A2 계산 ms).
     */
    public record Selection(Optional<Ask> ask, List<Ask> worth, int considered, int evaluations, long micros,
                            boolean capped) {
        public Selection(Optional<Ask> ask, List<Ask> worth, int considered, int evaluations, long micros) {
            this(ask, worth, considered, evaluations, micros, false);
        }

        public List<String> openKeys() {
            return worth.stream().map(a -> new Key(a.flowNo(), a.factId()) + (a.askNo() > 1 ? "(다시)" : "")).toList();
        }
    }

    /** 같은 흐름 안 사실 순서. */
    public enum Order { CATALOG, RULE }

    private final CaseJudge judge;
    private final Catalog catalog;
    private final Order order;
    private final Priority priority;
    private final int maxPerCase;
    private final Map<String, int[]> rulePos = new HashMap<>();   // 사실 → [슬롯 계산 순서, 규칙 ord, 원자 순서]

    public QuestionSelector(CaseJudge judge, Catalog catalog) {
        this(judge, catalog, Order.CATALOG);
    }

    public QuestionSelector(CaseJudge judge, Catalog catalog, Order order) {
        this(judge, catalog, order, Priority.ORDER, 0);
    }

    /**
     * maxPerCase: 사안당 질문(QUESTION 턴, 다시 묻기 포함) 상한, 0이면 없음(S7b). 다 쓰면 사안 등급·출구를 바꿀 수 있는 질문(Impact.EXIT)만
     * 더 묻고, 그것도 없으면 멈춘다(Selection.capped, 남은 후보는 worth).
     */
    public QuestionSelector(CaseJudge judge, Catalog catalog, Order order, Priority priority, int maxPerCase) {
        this.judge = judge;
        this.catalog = catalog;
        this.order = order;
        this.priority = priority;
        this.maxPerCase = maxPerCase;
        List<String> slotOrder = judge.converter().order();
        for (int si = 0; si < slotOrder.size(); si++) {
            for (SlotRules.Rule r : judge.converter().slots().get(slotOrder.get(si)).rules()) {
                int ai = 0;
                for (var a : io.github.overdozya.privacyask.rules.Condition.atoms(r.when())) {
                    String f = a.has("fact") ? a.get("fact").asString() : a.has("ledger") ? a.get("ledger").asString() : null;
                    if (f != null) {
                        rulePos.putIfAbsent(f, new int[] {si, r.ord(), ai});
                    }
                    ai++;
                }
            }
        }
    }

    public Order order() {
        return order;
    }

    public Priority priority() {
        return priority;
    }

    public int maxPerCase() {
        return maxPerCase;
    }

    /** 흐름 안 사실 순서 열쇠(작을수록 먼저). */
    private java.util.Comparator<Catalog.Fact> factOrder() {
        java.util.Comparator<Catalog.Fact> byOrd = java.util.Comparator.comparingInt(Catalog.Fact::ord);
        if (order == Order.CATALOG) {
            return byOrd;
        }
        int[] none = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        return java.util.Comparator.<Catalog.Fact>comparingInt(f -> rulePos.getOrDefault(f.factId(), none)[0])
                .thenComparingInt(f -> rulePos.getOrDefault(f.factId(), none)[1])
                .thenComparingInt(f -> rulePos.getOrDefault(f.factId(), none)[2])
                .thenComparing(byOrd);
    }

    /** 사안 상태에서: 물은 횟수는 QUESTION 턴으로 센다. cloudFlows: A1이 상대를 클라우드·IT 운영사로 낸 흐름. */
    public Selection select(CaseState state, Set<Integer> cloudFlows, Collection<PreliminaryExit.Reason> external) {
        String svc = state.caseRow().serviceName();
        Selection sel = select(judge.flows(state), askCounts(state), cloudFlows, external, svc != null && !svc.isBlank());
        long asked = state.turns().stream().filter(CaseState.Turn::isQuestion).count();
        return cap(sel, asked, maxPerCase);
    }

    /** 질문 상한(S7b): 넘어도 사안 등급·출구를 바꿀 수 있는 질문(EXIT)은 묻는다. 멈춰도 출구·등급은 다 물었을 때와 같다. */
    static Selection cap(Selection sel, long asked, int maxPerCase) {
        if (maxPerCase <= 0 || asked < maxPerCase || sel.ask().isEmpty()) {
            return sel;
        }
        Optional<Ask> exit = sel.worth().stream().filter(a -> a.impact() == Impact.EXIT).findFirst();
        return new Selection(exit, sel.worth(), sel.considered(), sel.evaluations(), sel.micros(), exit.isEmpty());
    }

    public static Map<Key, Integer> askCounts(CaseState state) {
        Map<Key, Integer> m = new HashMap<>();
        for (CaseState.Turn t : state.turns()) {
            if (t.isQuestion() && t.factId() != null) {
                m.merge(new Key(t.flowNo(), t.factId()), 1, Integer::sum);
            }
        }
        return m;
    }

    public Selection select(List<FlowFacts> flows, Map<Key, Integer> asks, Set<Integer> cloudFlows,
                            Collection<PreliminaryExit.Reason> external) {
        return select(flows, asks, cloudFlows, external, false);
    }

    /**
     * serviceKnown: 사안 서비스명(cases.service_name: API 1로 받았거나 F-S1에서 옮김)이 이미 있음 → F-S1(서비스명)을 묻지 않는다
     * (원장 후보는 사안 서비스명으로 연다, S7b).
     */
    public Selection select(List<FlowFacts> flows, Map<Key, Integer> asks, Set<Integer> cloudFlows,
                            Collection<PreliminaryExit.Reason> external, boolean serviceKnown) {
        long t0 = System.nanoTime();
        Run run = new Run(flows, asks, cloudFlows, external);
        List<Ask> worth = new ArrayList<>();
        // 공통 사실
        for (Catalog.Fact f : catalog.facts()) {
            if (!f.common()) {
                continue;
            }
            Key k = new Key(null, f.factId());
            FactState cur = flows.isEmpty() ? FactState.ABSENT : flows.get(0).fact(f.factId());
            if (SERVICE_FACT.equals(f.factId())) {
                if (!serviceKnown && eligible(cur, asks.getOrDefault(k, 0))) {
                    run.considered++;
                    worth.add(new Ask(null, f.factId(), asks.getOrDefault(k, 0) + 1,
                            "서비스명이 없어 원장 후보를 못 찾음(06 3.3 A3 기준선)", Impact.EXIT));
                }
                continue;
            }
            if (!ASKED_USAGES.contains(f.usage()) || !eligible(cur, asks.getOrDefault(k, 0))) {
                continue;
            }
            run.considered++;
            String why = run.worthCommon(f);
            if (why != null) {
                worth.add(new Ask(null, f.factId(), asks.getOrDefault(k, 0) + 1, why, run.lastImpact));
            }
        }
        // 흐름 사실: 흐름 번호 → 사실 순서
        List<Catalog.Fact> ordered = catalog.facts().stream().sorted(factOrder()).toList();
        for (int i = 0; i < flows.size(); i++) {
            FlowFacts flow = flows.get(i);
            for (Catalog.Fact f : ordered) {
                if (f.common() || !ASKED_USAGES.contains(f.usage()) || f.factId().startsWith("L")) {
                    continue;
                }
                Key k = new Key(flow.flowNo(), f.factId());
                int n = asks.getOrDefault(k, 0);
                if (!eligible(flow.fact(f.factId()), n) || run.excluded(i, f)) {
                    continue;
                }
                run.considered++;
                String why = run.worthFlow(i, f);
                if (why != null) {
                    worth.add(new Ask(flow.flowNo(), f.factId(), n + 1, why, run.lastImpact, raised(flow, f)));
                }
            }
        }
        if (priority == Priority.IMPACT) {
            // 안정 정렬: 같은 크기·같은 raised면 ORDER 순
            worth.sort(java.util.Comparator.<Ask>comparingInt(a -> a.impact().ordinal()).thenComparing(a -> !a.raised()));
        }
        long micros = (System.nanoTime() - t0) / 1000;
        return new Selection(worth.stream().findFirst(), List.copyOf(worth), run.considered, run.evaluations, micros);
    }

    static boolean eligible(FactState cur, int asked) {
        if (asked >= MAX_ASKS) {
            return false;
        }
        return cur.notFound() || FactState.UNKNOWN.equals(cur.status())
                || (FactState.INFERRED.equals(cur.status()) && "LEDGER".equals(cur.sourceKind()));
    }

    /** 보기 판: 단일·복수 모두 보기 하나씩, '잘 모르겠음'(kind UNKNOWN, 복수면 첫 보기 글로)은 미확인 판. */
    List<Map.Entry<String, FactState>> optionStates(Catalog.Fact f, boolean withUnknown) {
        List<Map.Entry<String, FactState>> out = new ArrayList<>();
        String unknownLabel = null;
        for (Catalog.Option o : f.options()) {
            if ("UNKNOWN".equals(o.kind())) {
                unknownLabel = o.label();
                continue;
            }
            out.add(Map.entry(o.label(), FactState.confirmed(o.label())));
        }
        if (withUnknown) {
            String l = unknownLabel == null ? "잘 모르겠음" : unknownLabel;
            out.add(Map.entry(l, FactState.unknown(l)));
        }
        return out;
    }

    /** 한 번 고르기 동안의 계산(기준 판정·슬롯 가정을 모아 둠). */
    private final class Run {
        final List<FlowFacts> flows;
        final Set<Integer> cloud;
        final Collection<PreliminaryExit.Reason> external;
        final List<IssueDeriver.FlowIssues> base = new ArrayList<>();
        final Map<String, Boolean> slotMatters = new HashMap<>();
        int considered, evaluations;
        Impact lastImpact = Impact.DETAIL;   // 바로 앞 worthFlow·worthCommon이 물을 가치를 찾았을 때 그 크기

        Run(List<FlowFacts> flows, Map<Key, Integer> asks, Set<Integer> cloud, Collection<PreliminaryExit.Reason> external) {
            this.flows = flows;
            this.cloud = cloud == null ? Set.of() : cloud;
            this.external = List.copyOf(external);
            for (FlowFacts f : flows) {
                base.add(judge.judgeFlow(f));
                evaluations++;
            }
        }

        boolean excluded(int i, Catalog.Fact f) {
            FlowFacts flow = flows.get(i);
            if (CLOUD_FACT.equals(f.factId()) && !cloud.contains(flow.flowNo())) {
                return true;
            }
            for (Map.Entry<String, List<String>> p : LEDGER_PAIRS.entrySet()) {
                if (p.getValue().contains(f.factId()) && FactState.MATCHED.equals(flow.fact(p.getKey()).status())) {
                    boolean slotOpen = false;
                    for (String s : f.slot().split("·")) {
                        SlotResult r = base.get(i).slots().get(s);
                        slotOpen |= r != null && SlotResult.OPEN.equals(r.status());
                    }
                    if (!slotOpen) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** 결과 = (그 흐름의 쟁점, 사안 등급, 출구). 다른 흐름은 기준 판정 그대로. */
        String outcome(int i, IssueDeriver.FlowIssues fi) {
            List<IssueDeriver.FlowIssues> all = new ArrayList<>(base);
            all.set(i, fi);
            CaseJudge.Judgment j = judge.combine(all, external);
            return issuesSig(fi) + " | " + j.caseGrade() + " | " + j.exit().exitType();
        }

        String worthFlow(int i, Catalog.Fact f) {
            FlowFacts flow = flows.get(i);
            Map<String, String> byOption = new LinkedHashMap<>();
            Set<String> exits = new LinkedHashSet<>(), names = new LinkedHashSet<>(), grades = new LinkedHashSet<>();
            for (Map.Entry<String, FactState> o : optionStates(f, true)) {
                IssueDeriver.FlowIssues fi = judge.judgeFlow(flow.with(f.factId(), o.getValue()));
                evaluations++;
                String out = outcome(i, fi);
                byOption.put(o.getKey(), out);
                String[] p = out.split(" \\| ");
                exits.add(p[p.length - 2] + "|" + p[p.length - 1]);
                names.add(namesSig(List.of(fi)));
                grades.add(gradesSig(List.of(fi)));
            }
            if (new LinkedHashSet<>(byOption.values()).size() > 1) {
                lastImpact = impact(exits, names, grades);
                return "답에 따라 바뀜: " + summary(byOption);
            }
            // (나) 함께 정하는 슬롯: 이 사실이 열림에 남은 OPEN 슬롯
            for (Map.Entry<String, SlotResult> e : base.get(i).slots().entrySet()) {
                SlotResult s = e.getValue();
                if (!SlotResult.OPEN.equals(s.status()) || !s.openFacts().contains(f.factId())) {
                    continue;
                }
                if (!slotMatters(i, e.getKey())) {
                    continue;
                }
                Set<String> slotStates = new LinkedHashSet<>();
                for (Map.Entry<String, FactState> o : optionStates(f, false)) {
                    SlotResult r = judge.converter().convert(flow.with(f.factId(), o.getValue())).get(e.getKey());
                    evaluations++;
                    slotStates.add(r.status() + r.values() + r.openFacts());
                }
                if (slotStates.size() > 1) {
                    lastImpact = Impact.DETAIL;
                    return "슬롯 " + e.getKey() + "이 열려 있고 그 값에 따라 결과가 바뀜, 이 답으로 좁혀짐";
                }
            }
            return null;
        }

        String worthCommon(Catalog.Fact f) {
            Map<String, String> byOption = new LinkedHashMap<>();
            Set<String> exits = new LinkedHashSet<>(), names = new LinkedHashSet<>(), grades = new LinkedHashSet<>();
            for (Map.Entry<String, FactState> o : optionStates(f, true)) {
                List<IssueDeriver.FlowIssues> all = new ArrayList<>();
                for (FlowFacts flow : flows) {
                    all.add(judge.judgeFlow(flow.with(f.factId(), o.getValue())));
                    evaluations++;
                }
                CaseJudge.Judgment j = judge.combine(all, external);
                StringBuilder sb = new StringBuilder();
                all.forEach(x -> sb.append(issuesSig(x)).append(" / "));
                byOption.put(o.getKey(), sb + " | " + j.caseGrade() + " | " + j.exit().exitType());
                exits.add(j.caseGrade() + "|" + j.exit().exitType());
                names.add(namesSig(all));
                grades.add(gradesSig(all));
            }
            if (new LinkedHashSet<>(byOption.values()).size() > 1) {
                lastImpact = impact(exits, names, grades);
                return "답에 따라 바뀜: " + summary(byOption);
            }
            return null;
        }

        /** 슬롯 값을 가정해(규칙이 낼 수 있는 값 하나씩 + SKIPPED) 결과가 OPEN일 때와 달라지나. */
        boolean slotMatters(int i, String slotKey) {
            return slotMatters.computeIfAbsent(i + ":" + slotKey, k -> {
                String open = outcome(i, base.get(i));
                Set<String> values = new LinkedHashSet<>();
                boolean skip = false;
                for (SlotRules.Rule r : judge.converter().slots().get(slotKey).rules()) {
                    values.addAll(r.values());
                    skip |= "SKIP".equals(r.effect());
                }
                List<SlotResult> tries = new ArrayList<>();
                values.forEach(v -> tries.add(SlotResult.forced(SlotResult.CONFIRMED, List.of(v))));
                if (skip) {
                    tries.add(SlotResult.forced(SlotResult.SKIPPED, List.of()));
                }
                for (SlotResult t : tries) {
                    IssueDeriver.FlowIssues fi = judge.judgeFlow(flows.get(i), Map.of(slotKey, t));
                    evaluations++;
                    if (!outcome(i, fi).equals(open)) {
                        return true;
                    }
                }
                return false;
            });
        }
    }

    /** 그 흐름에서 이 사실의 슬롯에 문의·답(원장 아님)으로 값이 정해진 다른 사실이 있나 = 문의가 이미 꺼낸 주제. */
    boolean raised(FlowFacts flow, Catalog.Fact f) {
        if (f.slot() == null) {
            return false;
        }
        Set<String> slots = new java.util.HashSet<>(List.of(f.slot().split("·")));
        for (Catalog.Fact g : catalog.facts()) {
            if (g.factId().equals(f.factId()) || g.common() || g.slot() == null) {
                continue;
            }
            boolean same = false;
            for (String sl : g.slot().split("·")) {
                same |= slots.contains(sl);
            }
            FactState st = flow.fact(g.factId());
            if (same && st.known() && !"LEDGER".equals(st.sourceKind())) {
                return true;
            }
        }
        return false;
    }

    static Impact impact(Set<String> exits, Set<String> names, Set<String> grades) {
        return exits.size() > 1 ? Impact.EXIT : names.size() > 1 ? Impact.ISSUE : grades.size() > 1 ? Impact.GRADE
                : Impact.DETAIL;
    }

    /** 흐름마다 쟁점 이름 집합. */
    static String namesSig(List<IssueDeriver.FlowIssues> fs) {
        StringBuilder sb = new StringBuilder();
        fs.forEach(f -> sb.append(f.issues().stream().map(IssueDeriver.Issue::issueName).sorted().toList()).append('/'));
        return sb.toString();
    }

    /** 흐름마다 (쟁점 이름, 등급). */
    static String gradesSig(List<IssueDeriver.FlowIssues> fs) {
        StringBuilder sb = new StringBuilder();
        fs.forEach(f -> sb.append(f.issues().stream().map(i -> i.issueName() + ":" + i.grade()).sorted().toList()).append('/'));
        return sb.toString();
    }

    /** 쟁점 집합의 비교 글: 이름·꾸러미·등급·규칙·조건·해석 쟁점(하위 꾸러미 포함). */
    static String issuesSig(IssueDeriver.FlowIssues fi) {
        StringBuilder sb = new StringBuilder();
        for (IssueDeriver.Issue i : fi.issues()) {
            sb.append(i.issueName()).append('/').append(i.packageId()).append('/').append(i.grade()).append('/')
                    .append(i.ruleIds()).append('/').append(i.conditionIds()).append('/').append(i.allInterpOn());
            i.opens().forEach(s -> sb.append('+').append(s.packageId()).append(s.conditionIds()));
            sb.append(';');
        }
        return sb.toString();
    }

    /** 보기별 결과 요약: 결과가 같은 보기끼리 묶어 짧게. */
    static String summary(Map<String, String> byOption) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        byOption.forEach((opt, out) -> groups.computeIfAbsent(out, x -> new ArrayList<>()).add(opt));
        List<String> parts = new ArrayList<>();
        groups.forEach((out, opts) -> parts.add(String.join("·", opts) + " → " + shortOutcome(out)));
        return String.join(" / ", parts);
    }

    private static String shortOutcome(String out) {
        // "쟁점/꾸러미/등급/…;… | 등급 | 출구" → "쟁점 등급, … | 사안 등급 | 출구"
        String[] p = out.split(" \\| ");
        List<String> issues = new ArrayList<>();
        for (String s : p[0].split(";")) {
            String[] x = s.split("/");
            if (x.length >= 3) {
                issues.add(x[0] + " " + x[2] + (s.contains("[I") || s.contains(".I") ? "(해석)" : ""));
            }
        }
        return "[" + String.join(", ", issues) + "] " + p[p.length - 2] + " " + p[p.length - 1];
    }
}
