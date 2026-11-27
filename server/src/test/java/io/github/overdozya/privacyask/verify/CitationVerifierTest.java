package io.github.overdozya.privacyask.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.overdozya.privacyask.answer.Answer;
import io.github.overdozya.privacyask.issues.CaseFacts;
import io.github.overdozya.privacyask.issues.CaseJudge;
import io.github.overdozya.privacyask.issues.ExampleCaseDb;
import io.github.overdozya.privacyask.issues.FlowFacts;
import io.github.overdozya.privacyask.issues.IssueDeriver;
import io.github.overdozya.privacyask.issues.PreliminaryExit;
import io.github.overdozya.privacyask.issues.SlotResult;
import io.github.overdozya.privacyask.knowledge.KnowledgeSnapshot;
import io.github.overdozya.privacyask.knowledge.LoadedDb;
import io.github.overdozya.privacyask.packages.PackageOpener;
import io.github.overdozya.privacyask.rules.ExitPolicy;

/**
 * S9 ②: A7 인용 검증 — 경우마다 1건(꾸러미 밖 키, 해시 불일치(스냅샷 판·A4 기록), 기준일에 시행 중 아님, 즉답 근거 아닌 문서만 인용,
 * 후보 밖 원장 행)과 통과 1건. 재료는 예시 사안(우리가 지음)의 쟁점으로 연 꾸러미(원문은 DB에서 읽음). 그리고 최종 출구 3건.
 */
class CitationVerifierTest {
    static final LocalDate D = LocalDate.of(2026, 12, 1);
    static KnowledgeSnapshot snap;
    static CaseJudge.Judgment example;
    static PackageOpener.Opened opened;
    static CitationVerifier cv;

    @BeforeAll
    static void load() {
        assumeThat(ExampleCaseDb.available() && LoadedDb.available()).as("case.db·적재 파일·Docker 없음").isTrue();
        snap = LoadedDb.snapshot();
        List<FlowFacts> flows = new ArrayList<>();
        for (int n : ExampleCaseDb.flowNos()) {
            flows.add(new FlowFacts(n, ExampleCaseDb.facts(n), CaseFacts.ledger(snap.ledger())));
        }
        example = CaseJudge.of(snap).judge(flows, List.of());
        List<PackageOpener.IssueRef> refs = new ArrayList<>();
        for (IssueDeriver.FlowIssues f : example.flows()) {
            for (IssueDeriver.Issue i : f.issues()) {
                refs.add(new PackageOpener.IssueRef(f.flowNo(), i.issueName(), i.packageId(), i.conditionIds(), i.interpOn(),
                        List.of()));
            }
        }
        opened = new PackageOpener(snap).open(snap.packages().currentVersion(), refs, D);
        cv = new CitationVerifier(snap);
    }

    /** 쟁점마다 첫 즉답 근거 키를 인용(+ 흐름 1 수집·이용 근거는 원장 행 첨부1-0040). */
    static List<CitationVerifier.IssueCites> goodCites() {
        List<CitationVerifier.IssueCites> out = new ArrayList<>();
        for (PackageOpener.OpenedIssue oi : opened.issues()) {
            String key = oi.keys().stream().filter(k -> basis(opened.sources().get(k))).findFirst().orElseThrow();
            List<String> rows = oi.flow() == 1 && "수집·이용 근거".equals(oi.issueName()) ? List.of("첨부1-0040") : List.of();
            out.add(new CitationVerifier.IssueCites(oi.flow(), oi.issueName(), List.of(key), rows));
        }
        return out;
    }

    static boolean basis(PackageOpener.Source s) {
        return s.answerBasis() && "ANSWER_BASIS".equals(s.usePolicy());
    }

    static CitationVerifier.Basis basis(Map<String, CitationVerifier.Cited> sources, Map<String, String> recorded,
                                        LocalDate date) {
        return new CitationVerifier.Basis(opened.keys(), sources, recorded, Set.of("첨부1-0040"), "20261028", date, true);
    }

    static Map<String, String> a4Hashes() {
        Map<String, String> m = new LinkedHashMap<>();
        opened.sources().forEach((k, s) -> m.put(k, s.textHash()));
        return m;
    }

    @Test
    void passes() {
        CitationVerifier.Report r = cv.verify(basis(CitationVerifier.sources(opened), a4Hashes(), D), goodCites());
        assertThat(r.problems()).isEmpty();
        assertThat(r.cites()).hasSize(6);   // 쟁점 5 + 원장 행 1
        assertThat(r.cites()).filteredOn(c -> "LEDGER_ROW".equals(c.kind())).singleElement()
                .satisfies(c -> assertThat(c.ledgerVersion()).isEqualTo("20261028"));
    }

    @Test
    void outsidePackageKey() {
        List<CitationVerifier.IssueCites> cs = new ArrayList<>(goodCites());
        CitationVerifier.IssueCites first = cs.get(0);
        cs.set(0, new CitationVerifier.IssueCites(first.flow(), first.issue(), List.of("개인정보보호법|71|1||"), List.of()));
        CitationVerifier.Report r = cv.verify(basis(CitationVerifier.sources(opened), a4Hashes(), D), cs);
        assertThat(r.problems()).extracting(CitationVerifier.Problem::kind).containsExactly(CitationVerifier.OUTSIDE_PACKAGE);
        assertThat(r.reason()).contains("넘긴 꾸러미 밖");
    }

    @Test
    void hashMismatchSnapshotAndRecorded() {
        String key = goodCites().get(0).cites().get(0);
        Map<String, CitationVerifier.Cited> src = new LinkedHashMap<>(CitationVerifier.sources(opened));
        CitationVerifier.Cited c = src.get(key);
        src.put(key, new CitationVerifier.Cited(key, c.kind(), "0".repeat(64), c.answerBasis(), c.usePolicy()));
        CitationVerifier.Report r = cv.verify(basis(src, a4Hashes(), D), goodCites());
        assertThat(r.problems()).extracting(CitationVerifier.Problem::kind).contains(CitationVerifier.HASH_MISMATCH);
        // A4 기록 해시만 다름(재시도 사이 스냅샷이 바뀐 경우)
        Map<String, String> rec = a4Hashes();
        rec.put(key, "f".repeat(64));
        CitationVerifier.Report r2 = cv.verify(basis(CitationVerifier.sources(opened), rec, D), goodCites());
        assertThat(r2.problems()).extracting(CitationVerifier.Problem::kind).containsOnly(CitationVerifier.HASH_MISMATCH);
        assertThat(r2.reason()).contains("A4 기록 해시");
    }

    @Test
    void notInForceOnReferenceDate() {
        CitationVerifier.Report r = cv.verify(basis(CitationVerifier.sources(opened), a4Hashes(), LocalDate.of(1990, 1, 1)),
                goodCites());
        assertThat(r.problems()).extracting(CitationVerifier.Problem::kind).contains(CitationVerifier.NOT_IN_FORCE);
        assertThat(r.problems()).filteredOn(p -> CitationVerifier.NOT_IN_FORCE.equals(p.kind())).hasSize(5);
    }

    @Test
    void onlyNonAnswerBasisDocument() {
        // 예시 꾸러미에서 즉답 근거가 아닌 원문 단위 수(지금 꾸러미 v2 항목은 모두 즉답 근거 표시일 수 있다)
        long nonBasis = opened.sources().values().stream().filter(x -> !basis(x)).count();
        System.out.println("S9 예시 꾸러미 원문 단위 " + opened.sources().size() + " 중 즉답 근거 아님 " + nonBasis);
        // 첫 쟁점이 인용한 키를 사용 정책 REFERENCE(해석 참고) 문서로 바꾼 인용 대상으로 본다
        CitationVerifier.IssueCites first = goodCites().get(0);
        String key = first.cites().get(0);
        Map<String, CitationVerifier.Cited> src = new LinkedHashMap<>(CitationVerifier.sources(opened));
        CitationVerifier.Cited c = src.get(key);
        src.put(key, new CitationVerifier.Cited(key, c.kind(), c.textHash(), c.answerBasis(), "REFERENCE"));
        List<CitationVerifier.IssueCites> cs = List.of(first);
        CitationVerifier.Report r = cv.verify(basis(src, a4Hashes(), D), cs);
        assertThat(r.problems()).extracting(CitationVerifier.Problem::kind).containsExactly(CitationVerifier.NO_ANSWER_BASIS);
        assertThat(r.cites()).filteredOn(x -> x.key().equals(key)).allSatisfy(x ->
                assertThat(x.use()).isEqualTo(CitationVerifier.USE_REFERENCE));
        assertThat(r.reason()).contains("즉답 근거 문서 인용 없음");
    }

    @Test
    void ledgerRowOutsideCandidates() {
        List<CitationVerifier.IssueCites> cs = new ArrayList<>(goodCites());
        CitationVerifier.IssueCites first = cs.get(0);
        cs.set(0, new CitationVerifier.IssueCites(first.flow(), first.issue(), first.cites(), List.of("첨부1-0039")));
        CitationVerifier.Report r = cv.verify(basis(CitationVerifier.sources(opened), a4Hashes(), D), cs);
        assertThat(r.problems()).extracting(CitationVerifier.Problem::kind).containsExactly(CitationVerifier.ROW_OUTSIDE);
    }

    // ───────── 최종 출구 ─────────

    static Answer answer(boolean conclusive, List<Answer.AddedReason> added) {
        List<Answer.IssueAnswer> is = new ArrayList<>();
        for (IssueDeriver.FlowIssues f : example.flows()) {
            for (IssueDeriver.Issue i : f.issues()) {
                is.add(new Answer.IssueAnswer(f.flowNo(), i.issueName(), i.packageId(), i.grade(), "의견", List.of(), List.of(),
                        List.of(), conclusive, conclusive, conclusive ? List.of() : List.of("모델이 미확정으로 냄"), List.of()));
            }
        }
        return new Answer(Answer.SPECIFIC, "2026-12-01", "INQUIRY", "2026-12-01", false, conclusive, is, List.of(), null,
                null, null, List.of(), List.of(), null, added, null, null);
    }

    static ExitAssembler assembler() {
        return new ExitAssembler(new ExitPolicy(snap.ruleSet().exitPolicy()));
    }

    @Test
    void exampleTransferReasons() {
        ExitAssembler.FinalExit e = assembler().specific(example, answer(false, List.of()),
                cv.verify(basis(CitationVerifier.sources(opened), a4Hashes(), D), goodCites()));
        assertThat(e.exitType()).isEqualTo("TRANSFER");
        assertThat(e.reasons()).extracting(ExitAssembler.Reason::code).containsExactly("REQUESTER_UNKNOWN",
                "NOT_CONCLUSIVE", "NOT_CONCLUSIVE", "NOT_CONCLUSIVE", "NOT_CONCLUSIVE", "NOT_CONCLUSIVE", "GRADE_POLICY");
        assertThat(e.conclusive()).isFalse();
    }

    @Test
    void lowGradeNoReasonsIsAnswer() {
        IssueDeriver.FlowIssues f1 = example.flow(1);
        Map<String, SlotResult> slots = new LinkedHashMap<>();
        f1.slots().forEach((k, v) -> slots.put(k, "cross_border".equals(k)
                ? new SlotResult(SlotResult.CONFIRMED, List.of("없음"), v.basisFacts(), List.of(), List.of(), List.of(), List.of())
                : v));
        IssueDeriver.FlowIssues low = new IssueDeriver.FlowIssues(1, slots,
                f1.issues().stream().filter(i -> "L".equals(i.grade())).toList(), List.of(), "L");
        CaseJudge.Judgment j = new CaseJudge.Judgment(List.of(low), "L", new PreliminaryExit.Exit("ANSWER", "L", List.of()));
        Answer a = new Answer(Answer.SPECIFIC, null, null, "2026-12-01", false, true, List.of(), List.of(), null, null, null,
                List.of(), List.of(), null, List.of(), null, null);
        ExitAssembler.FinalExit e = assembler().specific(j, a, null);
        assertThat(e.exitType()).isEqualTo("ANSWER");
        assertThat(e.reasons()).isEmpty();
        assertThat(e.conclusive()).isTrue();
    }

    @Test
    void openSlotCountsAsUnknown() {
        IssueDeriver.FlowIssues f1 = example.flow(1);
        Map<String, SlotResult> slots = new LinkedHashMap<>(f1.slots());
        slots.put("processing_mode", new SlotResult(SlotResult.OPEN, List.of(), List.of(), List.of(), List.of("F-M2"),
                List.of(), List.of()));
        IssueDeriver.FlowIssues open = new IssueDeriver.FlowIssues(1, slots, f1.issues(), f1.firedRules(), f1.grade());
        CaseJudge.Judgment j = new CaseJudge.Judgment(List.of(open), f1.grade(), example.exit());
        ExitAssembler.FinalExit e = assembler().specific(j, answer(true, List.of()), null);
        assertThat(e.openSlots()).singleElement().satisfies(o -> assertThat(o.blocking()).containsExactly("F-M2"));
        assertThat(e.reasons()).extracting(ExitAssembler.Reason::code).contains(ExitAssembler.OPEN_UNKNOWN);
        assertThat(e.exitType()).isEqualTo("TRANSFER");
        Set<String> codes = new LinkedHashSet<>();
        e.reasons().forEach(x -> codes.add(x.code()));
        System.out.println("S9 OPEN 슬롯 출구 사유: " + codes);
    }
}
