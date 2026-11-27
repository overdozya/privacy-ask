package io.github.overdozya.privacyask.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.overdozya.privacyask.issues.CaseFacts;
import io.github.overdozya.privacyask.issues.CaseJudge;
import io.github.overdozya.privacyask.issues.ExampleCaseDb;
import io.github.overdozya.privacyask.issues.FlowFacts;
import io.github.overdozya.privacyask.issues.IssueDeriver;
import io.github.overdozya.privacyask.knowledge.KnowledgeSnapshot;
import io.github.overdozya.privacyask.knowledge.LoadedDb;
import io.github.overdozya.privacyask.packages.ArticleList;
import io.github.overdozya.privacyask.packages.PackageOpener;

/**
 * S8 ④: A5 업무 검사와 코드가 정하는 것(손으로 쓴 출력). 예시 사안(우리가 지음)의 쟁점 5개와 A4가 연 꾸러미를 입력으로 둔다.
 * 거절: 꾸러미 밖 키, 후보 밖 원장 행, 넘기지 않은 쟁점, 쟁점 빠짐·두 번, 인용 없음, gap 사실 ID 밖·FACT_MISSING 사실 없음, 일반 G2 목록 밖 키·
 * 두 번·근거 없음 표시 없이 빈 고르기. 코드: 해석 쟁점이면 모델 conclusive=true여도 미확정, gap 해석 필요·꾸러미 밖 → 이관 사유,
 * 사실 부족 gap이 A2 후보를 말하면 질문, 과거 기준일 → 이관 사유.
 */
class AnswerCheckTest {
    static KnowledgeSnapshot k;
    static List<SpecificAnswerStep.FlowIn> flows;
    static PackageOpener.Opened opened;
    static final Set<String> ROWS = Set.of("첨부1-0040");
    static final PackageOpener.RefDate REF = PackageOpener.referenceDate(LocalDate.of(2026, 12, 1), "INQUIRY",
            LocalDate.of(2026, 11, 27));

    @BeforeAll
    static void load() {
        assumeThat(ExampleCaseDb.available() && LoadedDb.available()).as("case.db·적재 파일·Docker 없음").isTrue();
        k = LoadedDb.snapshot();
        List<FlowFacts> ff = new ArrayList<>();
        for (int n : ExampleCaseDb.flowNos()) {
            ff.add(new FlowFacts(n, ExampleCaseDb.facts(n), CaseFacts.ledger(k.ledger())));
        }
        CaseJudge.Judgment j = CaseJudge.of(k).judge(ff, List.of());
        flows = new ArrayList<>();
        List<PackageOpener.IssueRef> refs = new ArrayList<>();
        for (IssueDeriver.FlowIssues f : j.flows()) {
            List<SpecificAnswerStep.IssueIn> is = new ArrayList<>();
            for (IssueDeriver.Issue i : f.issues()) {
                is.add(new SpecificAnswerStep.IssueIn(f.flowNo(), i.issueName(), i.packageId(), i.grade(), i.ruleIds(),
                        i.conditionIds(), i.allInterpOn()));
                refs.add(new PackageOpener.IssueRef(f.flowNo(), i.issueName(), i.packageId(), i.conditionIds(),
                        i.interpOn(), List.of()));
            }
            flows.add(new SpecificAnswerStep.FlowIn(f.flowNo(), java.util.Map.of(), is));
        }
        opened = new PackageOpener(k).open("v2", refs, REF.openDate());
    }

    /** 쟁점마다 그 쟁점 꾸러미의 첫 키를 인용하는 맞는 출력. */
    static List<AnswerOutput.IssueOut> good(boolean conclusive) {
        List<AnswerOutput.IssueOut> out = new ArrayList<>();
        for (SpecificAnswerStep.FlowIn f : flows) {
            for (SpecificAnswerStep.IssueIn i : f.issues()) {
                String key = SpecificAnswerStep.openedIssue(opened, i).keys().get(0);
                out.add(new AnswerOutput.IssueOut(f.flow(), i.issue(), "의견", List.of("조치"), List.of(key),
                        f.flow() == 1 ? List.of("첨부1-0040") : List.of(), conclusive));
            }
        }
        return out;
    }

    /** 정해진 사실: 흐름 1의 F-D1(손으로 둠). 그 밖은 모름. */
    static final SpecificAnswerStep.Known KNOWN = (flow, fact) -> "F-D1".equals(fact) && (flow == null || flow == 1);

    static String check(List<AnswerOutput.IssueOut> issues, List<AnswerOutput.GapOut> gaps) {
        return SpecificAnswerStep.check(k, KNOWN, flows, opened.keys(), ROWS, new AnswerOutput(issues, gaps));
    }

    static List<AnswerOutput.IssueOut> replaceFirst(AnswerOutput.IssueOut x) {
        List<AnswerOutput.IssueOut> l = new ArrayList<>(good(true));
        l.set(0, x);
        return l;
    }

    @Test
    void goodPasses() {
        assertThat(check(good(true), List.of())).isNull();
    }

    @Test
    void rejectsKeyOutsidePackage() {
        AnswerOutput.IssueOut g = good(true).get(0);
        String bad = check(replaceFirst(new AnswerOutput.IssueOut(g.flow(), g.issue(), "의견", List.of(),
                List.of("개인정보보호법|71|1||"), List.of(), true)), List.of());
        assertThat(bad).contains("넘긴 꾸러미 밖");
    }

    @Test
    void rejectsRowOutsideCandidates() {
        AnswerOutput.IssueOut g = good(true).get(0);
        String bad = check(replaceFirst(new AnswerOutput.IssueOut(g.flow(), g.issue(), "의견", List.of(), g.cites(),
                List.of("첨부1-0039"), true)), List.of());
        assertThat(bad).contains("넘긴 후보 밖");
    }

    @Test
    void rejectsMissingDuplicateAndUnknownIssue() {
        List<AnswerOutput.IssueOut> l = new ArrayList<>(good(true));
        l.remove(4);
        assertThat(check(l, List.of())).contains("답이 0개");
        l = new ArrayList<>(good(true));
        l.add(l.get(0));
        assertThat(check(l, List.of())).contains("답이 2개");
        AnswerOutput.IssueOut g = good(true).get(0);
        assertThat(check(replaceFirst(new AnswerOutput.IssueOut(g.flow(), "국외 이전", "의견", List.of(), g.cites(),
                List.of(), true)), List.of())).contains("넘긴 쟁점이 아님");
        assertThat(check(replaceFirst(new AnswerOutput.IssueOut(g.flow(), g.issue(), "의견", List.of(), List.of(),
                List.of(), true)), List.of())).contains("인용 키 없음");
    }

    @Test
    void rejectsBadGaps() {
        assertThat(check(good(true), List.of(new AnswerOutput.GapOut("FACT_MISSING", 1, null, List.of(), "x"))))
                .contains("사실 ID 없음");
        assertThat(check(good(true), List.of(new AnswerOutput.GapOut("FACT_MISSING", 1, null, List.of("F-ZZ"), "x"))))
                .contains("사실 목록 밖");
        assertThat(check(good(true), List.of(new AnswerOutput.GapOut("INTERPRETATION", 3, null, List.of(), "x"))))
                .contains("gap 흐름 3 없음");
        assertThat(check(good(true), List.of(new AnswerOutput.GapOut("INTERPRETATION", 1, "목적 외 이용", List.of(), "x"))))
                .as("흐름 1에는 목적 외 이용이 없음").contains("없음");
    }

    /** S8b: FACT_MISSING은 아직 모르는 사실만, 정해진 사실의 '무엇인지'는 DETAIL_NEEDED. */
    @Test
    void factMissingOnlyUnknownDetailNeededOnlyKnown() {
        assertThat(check(good(true), List.of(new AnswerOutput.GapOut("FACT_MISSING", 1, null, List.of("F-D1"), "x"))))
                .contains("이미 정해짐");
        assertThat(check(good(true), List.of(new AnswerOutput.GapOut("DETAIL_NEEDED", 1, null, List.of("F-C2"), "x"))))
                .contains("아직 모름");
        assertThat(check(good(true), List.of(new AnswerOutput.GapOut("DETAIL_NEEDED", 1, null, List.of("F-D1"), "x"),
                new AnswerOutput.GapOut("FACT_MISSING", 1, null, List.of("F-C2"), "y")))).isNull();
        // DETAIL_NEEDED: 확인 필요 표시만(질문·이관 사유 없음, 확정 여부 안 바꿈)
        SpecificAnswerStep.Askable[] asked = new SpecificAnswerStep.Askable[1];
        Answer a = SpecificAnswerStep.answer(k, null, flows, opened, REF, new AnswerOutput(good(true), List.of(
                new AnswerOutput.GapOut("DETAIL_NEEDED", 1, null, List.of("F-D1"), "어떤 정보인지"))),
                List.of(new SpecificAnswerStep.Askable(1, "F-D1")), asked);
        assertThat(asked[0]).isNull();
        assertThat(a.addedReasons()).isEmpty();
        assertThat(a.gaps().get(0).handled()).isEqualTo("NOTE");
        assertThat(a.issues().stream().filter(i -> i.flow() == 1)).allSatisfy(i -> {
            assertThat(i.conclusive()).isTrue();
            assertThat(i.checks()).containsExactly("F-D1: 어떤 정보인지");
        });
        assertThat(a.conclusive()).isTrue();
    }

    @Test
    void interpretationIssueForcedNotConclusive() {
        // 흐름 1 첫 쟁점에 해석 쟁점이 켜진 것으로 두면, 모델이 conclusive=true여도 코드가 미확정
        List<SpecificAnswerStep.FlowIn> fl = new ArrayList<>(flows);
        SpecificAnswerStep.FlowIn f1 = fl.get(0);
        List<SpecificAnswerStep.IssueIn> is = new ArrayList<>(f1.issues());
        SpecificAnswerStep.IssueIn i0 = is.get(0);
        is.set(0, new SpecificAnswerStep.IssueIn(i0.flow(), i0.issue(), i0.packageId(), i0.grade(), i0.ruleIds(),
                i0.conditionIds(), List.of("I01")));
        fl.set(0, new SpecificAnswerStep.FlowIn(f1.flow(), f1.slots(), is));
        Answer a = SpecificAnswerStep.answer(k, null, fl, opened, REF, new AnswerOutput(good(true), List.of()),
                List.of(), new SpecificAnswerStep.Askable[1]);
        assertThat(a.issues().get(0).conclusiveModel()).isTrue();
        assertThat(a.issues().get(0).conclusive()).isFalse();
        assertThat(a.issues().get(0).notConclusive()).containsExactly("해석 쟁점 I01");
        assertThat(a.issues().subList(1, 5)).allMatch(Answer.IssueAnswer::conclusive);
        assertThat(a.conclusive()).isFalse();
        // 원문 요약은 코드가 키로 붙임(해시 = 기준일 판)
        assertThat(a.sources()).allSatisfy(s -> assertThat(s.textHash()).isEqualTo(opened.sources().get(s.key()).textHash()));
        assertThat(SpecificAnswerStep.interp(k, "v2", is.get(0)).get(0).get("q")).isNotNull();
    }

    @Test
    void gapsBecomeReasonsOrQuestion() {
        List<AnswerOutput.GapOut> gaps = List.of(
                new AnswerOutput.GapOut("INTERPRETATION", 2, "목적 외 이용", List.of(), "'합리적 관련' 해석"),
                new AnswerOutput.GapOut("OUTSIDE_PACKAGE", null, null, List.of(), "벌칙 조문"),
                new AnswerOutput.GapOut("FACT_MISSING", 1, null, List.of("F-C2"), "수탁사 서버 위치"));
        SpecificAnswerStep.Askable[] asked = new SpecificAnswerStep.Askable[1];
        Answer a = SpecificAnswerStep.answer(k, null, flows, opened, REF, new AnswerOutput(good(true), gaps),
                List.of(new SpecificAnswerStep.Askable(2, "F-B1"), new SpecificAnswerStep.Askable(1, "F-C2")), asked);
        assertThat(a.addedReasons()).extracting(Answer.AddedReason::code)
                .containsExactly(Answer.GAP_INTERPRETATION, Answer.GAP_OUTSIDE_PACKAGE);
        assertThat(a.gaps()).extracting(Answer.Gap::handled).containsExactly("REASON", "REASON", "QUESTION");
        assertThat(asked[0]).isEqualTo(new SpecificAnswerStep.Askable(1, "F-C2"));
        assertThat(a.issues()).noneMatch(Answer.IssueAnswer::conclusive);   // 사안 전체 gap(꾸러미 밖)
        // 후보가 없으면 사실 부족은 그대로 감
        SpecificAnswerStep.Askable[] none = new SpecificAnswerStep.Askable[1];
        Answer b = SpecificAnswerStep.answer(k, null, flows, opened, REF, new AnswerOutput(good(true), gaps.subList(2, 3)),
                List.of(), none);
        assertThat(none[0]).isNull();
        assertThat(b.gaps().get(0).handled()).isEqualTo("NONE");
        assertThat(b.addedReasons()).isEmpty();
        // 과거 기준일 → 이관 사유
        PackageOpener.RefDate past = PackageOpener.referenceDate(LocalDate.of(2026, 1, 5), "INQUIRY",
                LocalDate.of(2026, 11, 27));
        Answer c = SpecificAnswerStep.answer(k, null, flows, opened, past, new AnswerOutput(good(true), List.of()),
                List.of(), new SpecificAnswerStep.Askable[1]);
        assertThat(c.addedReasons()).extracting(Answer.AddedReason::code).containsExactly(Answer.PAST_REFERENCE_DATE);
        assertThat(c.pastReferenceDate()).isTrue();
    }

    @Test
    void generalChecks() {
        ArticleList list = new ArticleList(k, LocalDate.of(2026, 11, 27));
        assertThat(GeneralAnswerStep.check(list, new GeneralPicks(List.of(new GeneralPicks.Pick("개인정보보호법|26|", "x")),
                false, "r"))).isNull();
        assertThat(GeneralAnswerStep.check(list, new GeneralPicks(List.of(new GeneralPicks.Pick("개인정보보호법|71|", "x")),
                false, "r"))).contains("목록 밖");
        assertThat(GeneralAnswerStep.check(list, new GeneralPicks(List.of(new GeneralPicks.Pick("개인정보보호법|26|", "x"),
                new GeneralPicks.Pick("개인정보보호법|26|", "y")), false, "r"))).contains("두 번");
        assertThat(GeneralAnswerStep.check(list, new GeneralPicks(List.of(), false, "r"))).contains("no_basis");
        assertThat(GeneralAnswerStep.check(list, new GeneralPicks(List.of(), true, "r"))).isNull();
        Answer a = GeneralAnswerStep.answer(k, list, new GeneralPicks(List.of(new GeneralPicks.Pick("개인정보보호법|26|",
                "위탁")), false, "r"));
        assertThat(a.sources()).isNotEmpty();
        assertThat(a.guides()).as("제26조는 처리위탁 꾸러미의 핵심 → 안내서 절").isNotEmpty();
        assertThat(a.guides()).allMatch(g -> g.packageId().equals("PKG04"));
        assertThat(a.addedReasons()).isEmpty();
        Answer none = GeneralAnswerStep.answer(k, list, new GeneralPicks(List.of(), true, "벌칙"));
        assertThat(none.addedReasons()).extracting(Answer.AddedReason::code).containsExactly(Answer.NO_BASIS);
    }
}
