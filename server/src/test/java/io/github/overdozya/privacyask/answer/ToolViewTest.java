package io.github.overdozya.privacyask.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.overdozya.privacyask.issues.ExampleCaseDb;
import io.github.overdozya.privacyask.knowledge.LoadedDb;
import io.github.overdozya.privacyask.packages.PackageOpener;

/**
 * S10 A5 ↔ A6: 입력 줄이기('해석' 역할로만 쓰인 안내서 절은 글 없이), 업무 검사(글을 받지 않은 절 인용 거절·도구 요청 꼴),
 * A6 절 인용 → 답 출처 A6·즉답 근거 아님. 예시 사안(우리가 지음)의 쟁점 5개 꾸러미(AnswerCheckTest와 같은 입력).
 */
class ToolViewTest {
    @BeforeAll
    static void load() {
        assumeThat(ExampleCaseDb.available() && LoadedDb.available()).as("case.db·적재 파일·Docker 없음").isTrue();
        AnswerCheckTest.load();
    }

    static SpecificAnswerStep.ToolView reduce(Map<String, PackageOpener.Source> found) {
        return new SpecificAnswerStep.ToolView(true, 2, true, List.of(), found);
    }

    @Test
    void omittedAreInterpOnlyGuideSections() {
        PackageOpener.Opened o = AnswerCheckTest.opened;
        Set<String> om = SpecificAnswerStep.omitted(o, reduce(Map.of()));
        assertThat(om).isNotEmpty();
        int all = 0, cut = 0, guide = 0;
        for (PackageOpener.Source s : o.sources().values()) {
            all += s.text().length();
            if (PackageOpener.GUIDE_SECTION.equals(s.kind())) {
                guide += s.text().length();
            }
            if (om.contains(s.key())) {
                cut += s.text().length();
                assertThat(s.kind()).isEqualTo(PackageOpener.GUIDE_SECTION);
                for (PackageOpener.OpenedIssue oi : o.issues()) {
                    String role = oi.roles().get(s.key());
                    assertThat(role == null || "해석".equals(role)).isTrue();
                }
            }
        }
        System.out.println("S10 입력 줄이기(예시 꾸러미): 원문 " + all + "자 중 안내서 절 " + guide + "자, 뺀 '해석' 절 " + om.size()
                + "개 " + cut + "자 → 남는 원문 " + (all - cut) + "자");
        // 줄이기를 끄면 아무것도 빼지 않고, 읽은 절은 빼지 않는다
        assertThat(SpecificAnswerStep.omitted(o, new SpecificAnswerStep.ToolView(true, 2, false, List.of(), Map.of()))).isEmpty();
        String first = om.iterator().next();
        assertThat(SpecificAnswerStep.omitted(o, reduce(Map.of(first, o.sources().get(first))))).doesNotContain(first);
    }

    @Test
    void citingOmittedUnreadRejectedAndToolRequestShape() {
        PackageOpener.Opened o = AnswerCheckTest.opened;
        Set<String> om = SpecificAnswerStep.omitted(o, reduce(Map.of()));
        String sec = om.iterator().next();
        List<AnswerOutput.IssueOut> good = AnswerCheckTest.good(true);
        List<AnswerOutput.IssueOut> withSec = new ArrayList<>(good);
        AnswerOutput.IssueOut g = good.get(0);
        List<String> cites = new ArrayList<>(g.cites());
        cites.add(sec);
        withSec.set(0, new AnswerOutput.IssueOut(g.flow(), g.issue(), g.opinion(), g.actions(), cites, g.ledgerRows(), true));
        var tv = reduce(Map.of());
        String bad = SpecificAnswerStep.check(AnswerCheckTest.k, AnswerCheckTest.KNOWN, AnswerCheckTest.flows,
                SpecificAnswerStep.allowedKeys(o, tv), AnswerCheckTest.ROWS, new AnswerOutput(withSec, List.of()), tv, om);
        assertThat(bad).contains("글을 받지 않은 절").contains(sec);
        // 읽었으면(도구가 글을 붙임) 통과
        var read = reduce(Map.of(sec, o.sources().get(sec)));
        assertThat(SpecificAnswerStep.check(AnswerCheckTest.k, AnswerCheckTest.KNOWN, AnswerCheckTest.flows,
                SpecificAnswerStep.allowedKeys(o, read), AnswerCheckTest.ROWS, new AnswerOutput(withSec, List.of()), read,
                SpecificAnswerStep.omitted(o, read))).isNull();
        // 도구 요청 꼴: 검색어 없음, 없는 절, 모르는 도구
        List<AnswerOutput.ToolRequest> reqs = List.of(
                new AnswerOutput.ToolRequest("search_guides", " ", 1, null, null, "x"),
                new AnswerOutput.ToolRequest("read_section", null, null, null, "G00-없음", "x"),
                new AnswerOutput.ToolRequest("web_search", "x", null, null, null, "x"));
        String b2 = SpecificAnswerStep.check(AnswerCheckTest.k, AnswerCheckTest.KNOWN, AnswerCheckTest.flows,
                SpecificAnswerStep.allowedKeys(o, tv), AnswerCheckTest.ROWS, new AnswerOutput(good, List.of(), reqs), tv, Set.of());
        assertThat(b2).contains("검색어 없음").contains("G00-없음").contains("모르는 도구");
        // 도구를 주지 않은 호출이면 요청은 보지 않는다(코드가 무시)
        assertThat(SpecificAnswerStep.check(AnswerCheckTest.k, AnswerCheckTest.KNOWN, AnswerCheckTest.flows, o.keys(),
                AnswerCheckTest.ROWS, new AnswerOutput(good, List.of(), reqs), SpecificAnswerStep.ToolView.NONE, Set.of())).isNull();
    }

    /** 꾸러미 밖 A6 절을 인용하면 답 출처 A6·즉답 근거 아님, 꾸러미 밖 키 검사는 통과. */
    @Test
    void a6CiteBecomesA6SourceNotBasis() {
        PackageOpener.Opened o = AnswerCheckTest.opened;
        String outside = AnswerCheckTest.k.sections().get("G07-ec67e0c467").map(x -> x.sectionId()).orElse(null);
        assumeThat(outside).as("고른 절이 스냅샷에 없음").isNotNull();
        assumeThat(o.keys()).doesNotContain(outside);
        PackageOpener.Source a6 = new PackageOpener.Source(outside, PackageOpener.GUIDE_SECTION, 7, "안내서", "경로", "제목",
                "글", AnswerCheckTest.k.sections().hash(outside).orElseThrow(), null, null, List.of(), "ANSWER_BASIS", false);
        var tv = new SpecificAnswerStep.ToolView(true, 1, false, List.of(), Map.of(outside, a6));
        List<AnswerOutput.IssueOut> issues = new ArrayList<>(AnswerCheckTest.good(true));
        AnswerOutput.IssueOut g = issues.get(0);
        List<String> cites = new ArrayList<>(new LinkedHashSet<>(g.cites()));
        cites.add(outside);
        issues.set(0, new AnswerOutput.IssueOut(g.flow(), g.issue(), g.opinion(), g.actions(), cites, g.ledgerRows(), true));
        AnswerOutput out = new AnswerOutput(issues, List.of());
        assertThat(SpecificAnswerStep.check(AnswerCheckTest.k, AnswerCheckTest.KNOWN, AnswerCheckTest.flows, o.keys(),
                AnswerCheckTest.ROWS, out)).contains("꾸러미 밖");
        assertThat(SpecificAnswerStep.check(AnswerCheckTest.k, AnswerCheckTest.KNOWN, AnswerCheckTest.flows,
                SpecificAnswerStep.allowedKeys(o, tv), AnswerCheckTest.ROWS, out, tv, Set.of())).isNull();
        Answer a = SpecificAnswerStep.answer(AnswerCheckTest.k, null, AnswerCheckTest.flows, o, AnswerCheckTest.REF, out,
                List.of(), new SpecificAnswerStep.Askable[1], tv);
        Answer.Source s = a.sources().stream().filter(x -> x.key().equals(outside)).findFirst().orElseThrow();
        assertThat(s.fromA6()).isTrue();
        assertThat(s.answerBasis()).isFalse();
        assertThat(a.sources().stream().filter(x -> !x.key().equals(outside))).noneMatch(Answer.Source::fromA6);
    }
}
