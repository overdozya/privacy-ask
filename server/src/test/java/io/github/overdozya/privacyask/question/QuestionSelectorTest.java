package io.github.overdozya.privacyask.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.overdozya.privacyask.issues.CaseFacts;
import io.github.overdozya.privacyask.issues.CaseJudge;
import io.github.overdozya.privacyask.issues.ExampleCaseDb;
import io.github.overdozya.privacyask.issues.FlowFacts;
import io.github.overdozya.privacyask.knowledge.KnowledgeSnapshot;
import io.github.overdozya.privacyask.knowledge.LoadedDb;
import io.github.overdozya.privacyask.question.QuestionSelector.Key;
import io.github.overdozya.privacyask.rules.FactState;

/**
 * 06 7절 S5 ⑤: A2는 이미 정해진 값의 사실을 묻지 않고, 결정 6(국외 이전은 정보 이동 없이도)·결정 7(이메일뿐이면 야간 안 물음)이
 * 질문 조건 없이 "답에 따라 바뀌나" 계산으로 나온다. 예시 사안의 질문 차례와 A2 계산 시간도 잰다. 규칙 데이터는 적재 DB.
 */
class QuestionSelectorTest {
    static KnowledgeSnapshot snap;
    static CaseJudge judge;
    static QuestionSelector sel;

    @BeforeAll
    static void load() {
        assumeThat(LoadedDb.available()).as("적재 파일 또는 Docker 없음").isTrue();
        snap = LoadedDb.snapshot();
        judge = CaseJudge.of(snap);
        sel = new QuestionSelector(judge, snap.catalog());
    }

    static FlowFacts flow(int n, Map<String, FactState> facts) {
        return new FlowFacts(n, facts, CaseFacts.ledger(snap.ledger()));
    }

    static QuestionSelector.Selection select(List<FlowFacts> flows, Map<Key, Integer> asks, Set<Integer> cloud) {
        return sel.select(flows, asks, cloud, List.of());
    }

    static List<String> worthFacts(QuestionSelector.Selection s) {
        return s.worth().stream().map(QuestionSelector.Ask::factId).toList();
    }

    // ───────── 예시 사안 ─────────

    /** 예시 사안의 문의·원장 대조 뒤(A2 첫 차례, 단계 #7) 판: 답으로 받은 사실(흐름 1 F-T4·F-C2, 흐름 2 F-C2·F-B3)을 뺀다. */
    static List<FlowFacts> exampleStart() {
        Map<String, FactState> f1 = new LinkedHashMap<>(ExampleCaseDb.facts(1));
        Map<String, FactState> f2 = new LinkedHashMap<>(ExampleCaseDb.facts(2));
        f1.remove("F-T4");
        f1.put("F-C2", FactState.ABSENT);
        f2.remove("F-C2");
        f2.remove("F-B3");
        return new ArrayList<>(List.of(flow(1, f1), flow(2, f2)));
    }

    static FlowFacts answer(FlowFacts f, String fact, FactState v) {
        return new FlowFacts(f.flowNo(), f.with(fact, v).facts(), f.ledger());
    }

    @Test
    void exampleCaseQuestionOrder() {
        assumeThat(ExampleCaseDb.available()).as("case.db·sqlite3 없음").isTrue();
        List<FlowFacts> flows = exampleStart();
        Map<Key, Integer> asks = new HashMap<>();
        QuestionSelector.Selection s7 = select(flows, asks, Set.of());
        System.out.println("A2 #7 후보(물을 가치): " + s7.openKeys() + " (본 후보 " + s7.considered() + ", 판정 " + s7.evaluations()
                + "회, " + s7.micros() + "µs)");
        assertThat(s7.ask().orElseThrow()).extracting(QuestionSelector.Ask::flowNo, QuestionSelector.Ask::factId,
                QuestionSelector.Ask::askNo).containsExactly(1, "F-T4", 1);
        // 예시의 물을 사실 넷은 모두 물을 가치가 있다
        assertThat(s7.openKeys()).contains("1:F-T4", "1:F-C2", "2:F-C2", "2:F-B3");
        // 흐름 1은 L1 명시 일치라 F-B*·F-P1을 묻지 않음, 흐름 2 F-P1은 문의에서 나온 INFERRED라 묻지 않음
        assertThat(s7.openKeys()).doesNotContain("1:F-B3", "1:F-P1", "2:F-P1", "2:F-AD2", "1:F-T5", "2:F-T5");

        // #8 F-T4 아니오 → #9 흐름 1 F-C2
        flows.set(0, answer(flows.get(0), "F-T4", FactState.confirmed("아니오")));
        asks.put(new Key(1, "F-T4"), 1);
        assertThat(select(flows, asks, Set.of()).ask().orElseThrow())
                .extracting(QuestionSelector.Ask::flowNo, QuestionSelector.Ask::factId, QuestionSelector.Ask::askNo)
                .containsExactly(1, "F-C2", 1);
        // #10 잘 모르겠음 → #11 같은 사실 다시(ask_no 2)
        flows.set(0, answer(flows.get(0), "F-C2", FactState.unknown("잘 모르겠음")));
        asks.put(new Key(1, "F-C2"), 1);
        assertThat(select(flows, asks, Set.of()).ask().orElseThrow())
                .extracting(QuestionSelector.Ask::flowNo, QuestionSelector.Ask::factId, QuestionSelector.Ask::askNo)
                .containsExactly(1, "F-C2", 2);
        // #12 다시 잘 모르겠음 → 흐름 1은 끝(두 번 물음), 흐름 2로
        asks.put(new Key(1, "F-C2"), 2);
        QuestionSelector.Selection s13 = select(flows, asks, Set.of());
        System.out.println("A2 #13 후보: " + s13.openKeys() + " → " + s13.ask().orElseThrow());
        assertThat(s13.ask().orElseThrow().flowNo()).isEqualTo(2);
        assertThat(s13.openKeys()).noneMatch(k -> k.startsWith("1:"));
    }

    /**
     * 제안 순서 Order.RULE(규칙 우선순위): 예시 사안이 06 순서로는 흐름 2에서 F-B1부터 묻지만(위), 규칙 순서로는 F-B3(첫 규칙의 첫 사실)를
     * 묻고, 답(앞으로 받을 예정)으로 legal_basis가 정해져 F-B1·F-B2·F-B5가 저절로 빠진다. 질문은 예시와 같은 다섯(흐름 2 안 차례만 다름).
     */
    @Test
    void exampleCaseRuleOrderFiveQuestions() {
        assumeThat(ExampleCaseDb.available()).as("case.db·sqlite3 없음").isTrue();
        QuestionSelector rule = new QuestionSelector(judge, snap.catalog(), QuestionSelector.Order.RULE);
        List<FlowFacts> flows = exampleStart();
        Map<Key, Integer> asks = new HashMap<>();
        Map<String, FactState> answers = new LinkedHashMap<>();
        answers.put("1:F-T4", FactState.confirmed("아니오"));
        answers.put("1:F-C2", FactState.unknown("잘 모르겠음"));
        answers.put("2:F-C2", FactState.confirmed("아니오"));
        answers.put("2:F-B3", FactState.confirmed("앞으로 받을 예정"));
        List<String> asked = new ArrayList<>();
        for (int turn = 0; turn < 10; turn++) {
            var a = rule.select(flows, asks, Set.of(), List.of()).ask();
            if (a.isEmpty()) {
                break;
            }
            Key k = new Key(a.get().flowNo(), a.get().factId());
            asked.add(k + "#" + a.get().askNo());
            FactState v = answers.get(k.toString());
            assertThat(v).as("예시에 없는 질문 " + k).isNotNull();
            int idx = a.get().flowNo() - 1;
            flows.set(idx, answer(flows.get(idx), k.factId(), v));
            asks.merge(k, 1, Integer::sum);
        }
        System.out.println("A2 Order.RULE 예시 사안 질문: " + asked);
        assertThat(asked).containsExactly("1:F-T4#1", "1:F-C2#1", "1:F-C2#2", "2:F-B3#1", "2:F-C2#1");
        // 끝난 판의 판정은 재현표와 같다(흐름 2 legal_basis 동의(신규 필요))
        assertThat(judge.judgeFlow(flows.get(1)).slots().get("legal_basis").values()).containsExactly("동의(신규 필요)");
    }

    /** 예시 사안 마지막 판(판정 1): 남은 질문 없음(단계 #17 "ask": null). */
    @Test
    void exampleCaseFinalNoQuestion() {
        assumeThat(ExampleCaseDb.available()).as("case.db·sqlite3 없음").isTrue();
        List<FlowFacts> flows = List.of(flow(1, ExampleCaseDb.facts(1)), flow(2, ExampleCaseDb.facts(2)));
        Map<Key, Integer> asks = Map.of(new Key(1, "F-T4"), 1, new Key(1, "F-C2"), 2, new Key(2, "F-C2"), 1,
                new Key(2, "F-B3"), 1);
        QuestionSelector.Selection s = select(flows, asks, Set.of());
        assertThat(s.worth()).as(s.openKeys().toString()).isEmpty();
        assertThat(s.ask()).isEmpty();
    }

    // ───────── 이미 정해진 값 ─────────

    /** 04 1.9 끝 줄: F-AD1 = 보내지 않음이면 F-AD2~F-N2를 묻지 않는다. */
    @Test
    void alreadyDeterminedNotAsked() {
        Map<String, FactState> f = new LinkedHashMap<>();
        f.put("F-AD1", FactState.confirmed("보내지 않음"));
        f.put("F-D1", FactState.confirmed("없음"));
        f.put("F-D5", FactState.confirmed("받지 않음"));
        QuestionSelector.Selection s = select(List.of(flow(1, f)), Map.of(), Set.of());
        assertThat(worthFacts(s)).doesNotContain("F-AD1", "F-AD2", "F-AD3", "F-AD4", "F-AD5", "F-N1", "F-N2",
                "F-D2", "F-D6");
        // 같은 판에서 아직 정해지지 않은 것(정보 이동)은 묻는다
        assertThat(worthFacts(s)).contains("F-T1");
    }

    // ───────── 결정 6·7 ─────────

    /** 결정 6: 정보 이동이 없어도(F-T1 아니오) 해외 서버·해외 조회(F-C2·F-C3)는 묻는다. F-C1(국외로 넘기나)은 이동이 없으니 안 묻는다. */
    @Test
    void decision6CrossBorderWithoutTransfer() {
        Map<String, FactState> f = new LinkedHashMap<>();
        f.put("F-T1", FactState.confirmed("아니오(우리 회사 안에서만)"));
        QuestionSelector.Selection s = select(List.of(flow(1, f)), Map.of(), Set.of());
        assertThat(worthFacts(s)).contains("F-C2", "F-C3").doesNotContain("F-C1", "F-T2", "F-T3", "F-T4");
        // 같은 판에 F-T1 = 예면 F-C1도 묻는다
        f.put("F-T1", FactState.confirmed("예"));
        assertThat(worthFacts(select(List.of(flow(1, f)), Map.of(), Set.of()))).contains("F-C1", "F-C2", "F-C3");
    }

    /** 결정 7: 광고를 이메일로만 보내면 야간 전송(F-N1)을 묻지 않는다. 문자면 묻는다. */
    @Test
    void decision7NightNotAskedForEmailOnly() {
        Map<String, FactState> f = new LinkedHashMap<>();
        f.put("F-AD1", FactState.confirmed("이메일"));
        f.put("F-AD2", FactState.confirmed("전부 홍보"));
        f.put("F-AD3", FactState.confirmed("받음(기록 확인)"));
        f.put("F-AD4", FactState.confirmed("같음"));
        QuestionSelector.Selection email = select(List.of(flow(1, f)), Map.of(), Set.of());
        assertThat(worthFacts(email)).doesNotContain("F-N1");
        assertThat(judge.judgeFlow(flow(1, f)).slots().get("night_transmission").values()).containsExactly("아니오");
        f.put("F-AD1", FactState.confirmed("문자"));
        assertThat(worthFacts(select(List.of(flow(1, f)), Map.of(), Set.of()))).contains("F-N1");
    }

    // ───────── 후보 고르기 ─────────

    /** F-T5는 A1이 상대를 클라우드·IT 운영사로 낸 흐름에서만 후보(S3 넘길 것). */
    @Test
    void cloudFactOnlyForCloudFlows() {
        Map<String, FactState> f = new LinkedHashMap<>();
        f.put("F-T1", FactState.confirmed("예"));
        f.put("F-T2", FactState.confirmed("우리 회사 일을 대신함(성과는 우리)"));
        f.put("F-T3", FactState.confirmed("예"));
        assertThat(worthFacts(select(List.of(flow(1, f)), Map.of(), Set.of()))).doesNotContain("F-T5");
        assertThat(worthFacts(select(List.of(flow(1, f)), Map.of(), Set.of(1)))).contains("F-T5");
    }

    static FactState l1(String rowId) {
        return new FactState(FactState.MATCHED, List.of("명시 일치"), List.of(rowId), "LEDGER");
    }

    /**
     * L1 명시 일치면 짝 F 사실(F-B*·F-P1)을 묻지 않는다. 원장 행이 슬롯을 못 정하면(법적 근거가 제23조만, S3 벡터 B-04)
     * legal_basis가 OPEN이라 F-B*를 묻는다.
     */
    @Test
    void ledgerPairExcludedUnlessSlotStillOpen() {
        Map<String, FactState> f = new LinkedHashMap<>();
        f.put("F-T1", FactState.confirmed("예"));
        f.put("F-T2", FactState.confirmed("그 회사 자기 일·서비스·마케팅(성과는 그 회사)"));
        f.put("L1", l1("첨부1-0040"));     // 예시 사안 흐름 1의 행(법적 근거: 동의)
        List<String> w = worthFacts(select(List.of(flow(1, f)), Map.of(), Set.of()));
        assertThat(w).doesNotContain("F-B1", "F-B2", "F-B3", "F-B4", "F-B5", "F-P1");
        f.put("L1", l1("첨부1-0011"));     // 법적 근거가 제23조만(B-04)
        assertThat(judge.judgeFlow(flow(1, f)).slots().get("legal_basis").status()).isEqualTo("OPEN");
        assertThat(worthFacts(select(List.of(flow(1, f)), Map.of(), Set.of()))).contains("F-B3").doesNotContain("F-P1");
    }

    /**
     * 두 사실이 함께 정하는 슬롯(processing_mode: F-M1·F-M2, F-A1~F-A4): 사안이 이미 고 등급(민감정보)이라 출구가 안 바뀌어도
     * 슬롯이 OPEN이면 묻는다((나) 보탬). 하나씩 보면 늘 OPEN이라 (가)만으로는 안 나온다.
     */
    @Test
    void jointSlotStillAsked() {
        Map<String, FactState> f = new LinkedHashMap<>();
        f.put("F-D1", FactState.confirmed("건강·병력·진료"));
        QuestionSelector.Selection s = select(List.of(flow(1, f)), Map.of(), Set.of());
        QuestionSelector.Ask m1 = s.worth().stream().filter(a -> a.factId().equals("F-M1")).findFirst().orElseThrow();
        assertThat(m1.why()).as("(나) 보탬으로 나옴").startsWith("슬롯 ");
        assertThat(worthFacts(s)).contains("F-A1");
    }

    @Test
    void serviceNameAskedFirst() {
        Map<String, FactState> f = new LinkedHashMap<>();
        f.put("F-T1", FactState.confirmed("예"));
        QuestionSelector.Selection s = select(List.of(flow(1, f)), Map.of(), Set.of());
        assertThat(s.ask().orElseThrow()).extracting(QuestionSelector.Ask::flowNo, QuestionSelector.Ask::factId)
                .containsExactly(null, "F-S1");
        f.put("F-S1", FactState.confirmed("서비스명(자유 입력)"));
        assertThat(select(List.of(flow(1, f)), Map.of(), Set.of()).ask().orElseThrow().factId()).isNotEqualTo("F-S1");
    }

    @Test
    void askedTwiceNotAgain() {
        Map<String, FactState> f = new LinkedHashMap<>();
        f.put("F-S1", FactState.confirmed("서비스명(자유 입력)"));
        f.put("F-C2", FactState.unknown("잘 모르겠음"));
        assertThat(worthFacts(select(List.of(flow(1, f)), Map.of(new Key(1, "F-C2"), 1), Set.of()))).contains("F-C2");
        assertThat(worthFacts(select(List.of(flow(1, f)), Map.of(new Key(1, "F-C2"), 2), Set.of()))).doesNotContain("F-C2");
    }

    @Test
    void questionText() {
        var fact = snap.catalog().fact("F-C2").orElseThrow();
        QuestionText.Text t1 = QuestionText.of(fact, 1, "문자 발송 대행 업체");
        assertThat(t1.text()).startsWith("[문자 발송 대행 업체] ").endsWith(fact.question());
        assertThat(t1.choices()).containsExactly("예(나라)", "아니오", "잘 모르겠음");
        QuestionText.Text t2 = QuestionText.of(fact, 2, null);
        assertThat(t2.text()).startsWith(fact.question()).contains("예: 예(나라) / 아니오").contains("잘 모르겠음");
        assertThat(t2.choices()).isEqualTo(t1.choices());
    }

    /** 06 6.4: A2 계산 ms(후보 × 보기 × 흐름). 예시 사안 첫 차례(흐름 2, 후보가 가장 많을 때)를 여러 번 잰다. */
    @Test
    void a2Timing() {
        assumeThat(ExampleCaseDb.available()).as("case.db·sqlite3 없음").isTrue();
        List<FlowFacts> start = exampleStart();
        List<FlowFacts> empty = List.of(flow(1, Map.of()), flow(2, Map.of()), flow(3, Map.of()));
        for (int i = 0; i < 30; i++) {     // 데우기
            select(start, Map.of(), Set.of());
        }
        for (Object[] c : new Object[][] {{"예시 첫 차례(흐름 2)", start}, {"빈 흐름 3개", empty}}) {
            @SuppressWarnings("unchecked") List<FlowFacts> fl = (List<FlowFacts>) c[1];
            List<Long> us = new ArrayList<>();
            QuestionSelector.Selection last = null;
            for (int i = 0; i < 50; i++) {
                last = select(fl, Map.of(), Set.of());
                us.add(last.micros());
            }
            us.sort(Long::compare);
            System.out.printf("A2 계산 %s: p50 %.2f ms, 최대 %.2f ms (본 후보 %d, 흐름 판정 %d회, 물을 가치 %d)%n", c[0],
                    us.get(us.size() / 2) / 1000.0, us.get(us.size() - 1) / 1000.0, last.considered(), last.evaluations(),
                    last.worth().size());
            assertThat(us.get(us.size() / 2)).as("p50 < 300ms(06 6.1 LLM 없는 턴 예산 전체)").isLessThan(300_000);
        }
    }
}
