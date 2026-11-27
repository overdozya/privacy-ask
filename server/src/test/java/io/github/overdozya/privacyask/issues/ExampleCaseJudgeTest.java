package io.github.overdozya.privacyask.issues;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.overdozya.privacyask.knowledge.KnowledgeSnapshot;
import io.github.overdozya.privacyask.knowledge.LoadedDb;
import io.github.overdozya.privacyask.rules.ExitPolicy;

/**
 * 06 7절 S5 ④: 예시 사안(data/수집/예시사안.json → case.db, 재현표와 같은 DB)의 지금 사실 판에서 흐름별 슬롯·쟁점·사안 등급·
 * 예비 출구를 계산하면 재현표의 판정 1과 같다. 결정 4(흐름마다 한 벌, 쟁점은 흐름별, 등급은 가장 높은 것)도 이 사안으로 본다.
 */
class ExampleCaseJudgeTest {
    static KnowledgeSnapshot snap;
    static CaseJudge judge;
    static CaseJudge.Judgment got;

    @BeforeAll
    static void load() {
        assumeThat(ExampleCaseDb.available() && LoadedDb.available()).as("case.db·sqlite3·적재 파일 또는 Docker 없음").isTrue();
        snap = LoadedDb.snapshot();
        judge = CaseJudge.of(snap);
        List<FlowFacts> flows = new ArrayList<>();
        for (int n : ExampleCaseDb.flowNos()) {
            flows.add(new FlowFacts(n, ExampleCaseDb.facts(n), CaseFacts.ledger(snap.ledger())));
        }
        got = judge.judge(flows, List.of());
    }

    @Test
    void slotsEqualReplayTable() {
        for (int n : ExampleCaseDb.flowNos()) {
            Map<String, Map<String, Object>> want = ExampleCaseDb.judgedSlots(n);
            assertThat(want).hasSize(8);
            Map<String, SlotResult> slots = got.flow(n).slots();
            for (Map.Entry<String, Map<String, Object>> e : want.entrySet()) {
                SlotResult s = slots.get(e.getKey());
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("status", s.status());
                g.put("values", s.values());
                g.put("basis_facts", s.basisFacts());
                g.put("unknown_facts", s.unknownFacts());
                Map<String, Object> w = new LinkedHashMap<>(e.getValue());
                assertThat(g.get("basis_facts")).as(n + " " + e.getKey())
                        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                        .containsExactlyInAnyOrderElementsOf((List<?>) w.get("basis_facts"));
                g.remove("basis_facts");
                w.remove("basis_facts");
                assertThat(g).as("흐름 " + n + " " + e.getKey()).isEqualTo(w);
            }
        }
    }

    @Test
    void issuesGradeAndExitEqualReplayTable() {
        for (int n : ExampleCaseDb.flowNos()) {
            List<List<Object>> mine = new ArrayList<>();
            for (IssueDeriver.Issue i : got.flow(n).issues()) {
                mine.add(List.of(i.issueName(), i.packageId(), i.grade(), i.ruleIds(), i.conditionIds(), i.interpOn()));
                assertThat(i.opens()).as("PKG14는 열리지 않음(F-P1이 이어지는 새 용도가 아님)").isEmpty();
            }
            assertThat(mine).as("흐름 " + n).isEqualTo(ExampleCaseDb.judgedIssues(n));
        }
        var j = ExampleCaseDb.judgment();
        assertThat(got.caseGrade()).isEqualTo(j.get("case_grade").asString()).isEqualTo("H");
        assertThat(got.exit().exitType()).isEqualTo(j.get("exit_type").asString()).isEqualTo(ExitPolicy.TRANSFER);
        // 재현표 출구 사유의 '현업 미확인: 흐름 1 cross_border(F-C2)'
        assertThat(got.exit().reasons()).containsExactly(
                new PreliminaryExit.Reason(PreliminaryExit.REQUESTER_UNKNOWN, 1, "cross_border: F-C2"));
        System.out.println("예시 사안 판정: 등급 " + got.caseGrade() + ", 출구 " + got.exit().exitType() + ", 사유 "
                + got.exit().reasons() + ", 흐름 등급 " + got.flows().stream().map(IssueDeriver.FlowIssues::grade).toList());
    }

    /** 결정 4: 흐름 1은 M(처리위탁), 흐름 2는 H(목적 외 이용) → 사안 H. 같은 쟁점 이름(수집·이용 근거)이 흐름마다 다른 등급으로 남는다. */
    @Test
    void decision4PerFlowSlotsAndMaxGrade() {
        assertThat(got.flow(1).grade()).isEqualTo("M");
        assertThat(got.flow(2).grade()).isEqualTo("H");
        assertThat(got.flow(1).slots().get("legal_basis").values()).containsExactly("동의(기존 범위 내)");
        assertThat(got.flow(2).slots().get("legal_basis").values()).containsExactly("동의(신규 필요)");
        assertThat(got.flow(1).issues().get(0).grade()).isEqualTo("L");
        assertThat(got.flow(2).issues().get(0).grade()).isEqualTo("M");
        assertThat(got.caseGrade()).isEqualTo("H");
    }
}
