package io.github.overdozya.privacyask.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.overdozya.privacyask.issues.CaseJudge;
import io.github.overdozya.privacyask.issues.FlowFacts;
import io.github.overdozya.privacyask.knowledge.KnowledgeSnapshot;
import io.github.overdozya.privacyask.knowledge.LoadedDb;
import io.github.overdozya.privacyask.record.CaseInput;
import io.github.overdozya.privacyask.record.CaseState;

/** S7이 넘길 CaseState(CaseReader.load)에서 바로: 공통 사실 + 흐름 사실의 지금 판, 물은 횟수 = QUESTION 턴, 질문 글. */
class CaseStateInputTest {
    static final LocalDateTime AT = LocalDateTime.of(2026, 11, 27, 0, 0);

    static CaseState.Fact fact(long id, Integer flow, String fid, int rev, String status, String... labels) {
        return new CaseState.Fact(id, flow, fid, rev, status, "INQUIRY", null, 3, "q", null, null, AT,
                java.util.Arrays.stream(labels).map(CaseInput.Value::of).toList(), List.of());
    }

    static CaseState.Turn question(int no, Integer flow, String fid, int askNo) {
        return new CaseState.Turn(no, "AGENT", "QUESTION", flow, fid, askNo, null, "질문", List.of(), 7, AT);
    }

    @Test
    void fromCaseState() {
        assumeThat(LoadedDb.available()).as("적재 파일 또는 Docker 없음").isTrue();
        KnowledgeSnapshot snap = LoadedDb.snapshot();
        CaseJudge judge = CaseJudge.of(snap);
        CaseState.CaseRow row = new CaseState.CaseRow("c", "r", "문의", "T 멤버십", "SPECIFIC", null, "ASKING", "v1.0", "v2",
                "20261028", null, null, AT, AT, null);
        CaseState st = new CaseState(row,
                List.of(new CaseState.Flow(1, "위탁", "회사", "문자 발송 대행 업체", "T 멤버십", 3)),
                List.of(question(1, 1, "F-C2", 1), new CaseState.Turn(2, "USER", "ANSWER", 1, null, null, 1, "잘 모르겠음",
                        List.of("잘 모르겠음"), 7, AT)),
                List.of(fact(1, null, "F-S1", 1, "CONFIRMED", "서비스명(자유 입력)"),
                        fact(2, 1, "F-T1", 1, "CONFIRMED", "아니오(우리 회사 안에서만)"),
                        fact(3, 1, "F-C2", 1, "NOT_FOUND"),
                        fact(4, 1, "F-C2", 2, "UNKNOWN", "잘 모르겠음")),
                List.of());
        List<FlowFacts> flows = judge.flows(st);
        assertThat(flows).hasSize(1);
        assertThat(flows.get(0).fact("F-S1").status()).isEqualTo("CONFIRMED");
        assertThat(flows.get(0).fact("F-C2").status()).as("지금 판 = rev 2").isEqualTo("UNKNOWN");
        assertThat(QuestionSelector.askCounts(st)).containsEntry(new QuestionSelector.Key(1, "F-C2"), 1);

        QuestionSelector sel = new QuestionSelector(judge, snap.catalog());
        QuestionSelector.Selection s = sel.select(st, Set.of(), List.of());
        assertThat(s.ask()).isPresent();
        QuestionSelector.Ask a = s.worth().stream().filter(x -> x.factId().equals("F-C2")).findFirst().orElseThrow();
        assertThat(a.askNo()).as("'잘 모르겠음' 뒤 다시 한 번").isEqualTo(2);
        QuestionText.Text t = QuestionText.of(snap.catalog().fact("F-C2").orElseThrow(), a.askNo(),
                st.flow(1).orElseThrow().destination());
        assertThat(t.text()).startsWith("[문자 발송 대행 업체] ").contains("예: ");

        CaseJudge.Judgment j = judge.judge(st, List.of());
        assertThat(j.flow(1).slots().get("cross_border").status()).isEqualTo("UNKNOWN");
        assertThat(j.exit().exitType()).isEqualTo("TRANSFER");
    }
}
