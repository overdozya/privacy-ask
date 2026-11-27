package io.github.overdozya.privacyask.packages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
import io.github.overdozya.privacyask.knowledge.LawUnits;
import io.github.overdozya.privacyask.knowledge.LoadedDb;
import tools.jackson.databind.JsonNode;

/**
 * S8 A4 ①②③: 예시 사안(data/수집/예시사안.json → case.db, 우리가 지음)의 쟁점 5개로 연 꾸러미가 꾸러미 v2 파일
 * (data/수집/꾸러미_v2.json, 적재 DB와 따로 읽음)의 항목과 같다(항목 수·키, conds 거르기). 연결 한 단계 · 시행 예정 표시 · 과거 기준일,
 * G2 목록 257조. 원문은 테스트에 적지 않는다(DB에서 읽음).
 */
class PackageOpenerTest {
    static final Path V2 = LoadedDb.DATA.resolve("수집").resolve("꾸러미_v2.json");
    static KnowledgeSnapshot snap;
    static PackageOpener opener;
    static CaseJudge.Judgment example;

    @BeforeAll
    static void load() {
        assumeThat(ExampleCaseDb.available() && LoadedDb.available() && Files.isRegularFile(V2))
                .as("case.db·적재 파일·Docker·꾸러미_v2.json 없음").isTrue();
        snap = LoadedDb.snapshot();
        opener = new PackageOpener(snap);
        List<FlowFacts> flows = new ArrayList<>();
        for (int n : ExampleCaseDb.flowNos()) {
            flows.add(new FlowFacts(n, ExampleCaseDb.facts(n), CaseFacts.ledger(snap.ledger())));
        }
        example = CaseJudge.of(snap).judge(flows, List.of());
    }

    static List<PackageOpener.IssueRef> refs(CaseJudge.Judgment j) {
        List<PackageOpener.IssueRef> out = new ArrayList<>();
        for (IssueDeriver.FlowIssues f : j.flows()) {
            for (IssueDeriver.Issue i : f.issues()) {
                out.add(new PackageOpener.IssueRef(f.flowNo(), i.issueName(), i.packageId(), i.conditionIds(), i.interpOn(),
                        i.opens().stream().map(s -> new PackageOpener.SubRef(s.packageId(), s.conditionIds(), s.interpOn()))
                                .toList()));
            }
        }
        return out;
    }

    /** 꾸러미 v2 파일에서 그 꾸러미의 항목 키(conds가 없거나 맞은 조건과 겹침), 파일 순서. */
    static List<String> fileKeys(String packageId, List<String> matched) throws Exception {
        JsonNode d = LoadedDb.JSON.readTree(Files.readString(V2));
        List<String> out = new ArrayList<>();
        for (JsonNode p : d.get("packages")) {
            if (!p.get("package_id").asString().equals(packageId)) {
                continue;
            }
            for (JsonNode it : p.get("items")) {
                JsonNode conds = it.get("conds");
                boolean ok = conds == null || conds.isNull() || conds.isEmpty();
                if (!ok) {
                    for (JsonNode c : conds) {
                        ok |= matched.contains(c.asString());
                    }
                }
                if (ok) {
                    out.add(it.get("kind").asString() + " " + it.get("key").asString());
                }
            }
        }
        return out;
    }

    @Test
    void exampleFiveIssuesMatchPackageV2File() throws Exception {
        List<PackageOpener.IssueRef> refs = refs(example);
        assertThat(refs).extracting(r -> r.flow() + ":" + r.packageId() + r.conditionIds())
                .containsExactly("1:PKG01[C1]", "1:PKG04[C1]", "2:PKG01[C2]", "2:PKG02[C1]", "2:PKG03[C1]");
        LocalDate date = LocalDate.of(2026, 12, 1);   // 예시 사안 기준일(F-S3, INQUIRY)
        long[] us = new long[5];
        PackageOpener.Opened o = null;
        for (int i = 0; i < us.length; i++) {
            o = opener.open("v2", refs, date);
            us[i] = o.micros();
        }
        int items = 0;
        for (PackageOpener.OpenedIssue oi : o.issues()) {
            List<String> mine = oi.items().stream().map(it -> it.targetKind() + " " + it.targetKey()).toList();
            List<String> want = fileKeys(oi.packageId(), oi.conditionIds());
            assertThat(mine).as(oi.flow() + " " + oi.packageId()).containsExactlyElementsOf(want);
            assertThat(oi.missing()).as("기준일에 판이 없는 키").isEmpty();
            items += mine.size();
            System.out.printf("S8 A4 예시 흐름 %d %s %s: 항목 %d, 키 %d(연결 %d)%n", oi.flow(), oi.packageId(),
                    oi.conditionIds(), mine.size(), oi.keys().size(), oi.linked().size());
        }
        // 원문 단위: 조문은 기준일에 시행 중인 판, 안내서 절은 절 해시
        for (PackageOpener.Source s : o.sources().values()) {
            assertThat(s.text()).as(s.key()).isNotBlank();
            assertThat(s.textHash()).as(s.key()).isNotBlank();
            if (PackageOpener.LAW_UNIT.equals(s.kind())) {
                assertThat(snap.laws().inForce(s.key(), date).orElseThrow().textHash()).isEqualTo(s.textHash());
            } else if (PackageOpener.GUIDE_SECTION.equals(s.kind())) {
                assertThat(snap.sections().hash(s.key())).contains(s.textHash());
            }
        }
        System.out.printf("S8 A4 예시: 쟁점 5, 항목 %d, 원문 단위 %d, %d자, 시행 예정 %s, 열기 µs %s%n", items,
                o.sources().size(), o.chars(), o.anyUpcoming(), java.util.Arrays.toString(us));
        assertThat(us[us.length - 1]).as("메모리 열기(µs)").isLessThan(50_000);
    }

    @Test
    void linksOneStepOnly() {
        // PKG13 광고성 정보 전송: 망법 제50조 → 시행령 제61·62조(위임) → 별표 6(별표). 꾸러미 조건 전부를 맞은 것으로 연다
        var p = snap.packages().current("PKG13").orElseThrow();
        PackageOpener.IssueRef ref = new PackageOpener.IssueRef(1, p.issueName(), "PKG13", List.copyOf(p.conditionIds()),
                List.of(), List.of());
        PackageOpener.Opened o = opener.open("v2", List.of(ref), LocalDate.of(2026, 11, 27));
        PackageOpener.OpenedIssue oi = o.issues().get(0);
        Set<String> itemKeys = new LinkedHashSet<>();
        oi.items().forEach(it -> itemKeys.addAll(it.keys()));
        System.out.println("S8 A4 PKG13 연결: " + oi.linked());
        assertThat(oi.linked()).isNotEmpty();
        // 연결로 붙은 키는 항목 키가 아니고, 연결의 출발은 늘 항목 키(붙인 것에서 다시 따라가지 않음)
        assertThat(oi.linked()).allSatisfy(l -> {
            assertThat(itemKeys).contains(l.fromKey());
            assertThat(itemKeys).doesNotContain(l.key());
            assertThat(oi.roles().get(l.key())).isEqualTo(PackageOpener.ROLE_LINKED);
            assertThat(Set.of("위임", "별표")).contains(l.linkType());
        });
    }

    @Test
    void upcomingAmendmentMarked() {
        // 자동화된 결정(PKG10)의 보호법 제37조의2 제1항에 시행 예정 판(PENDING)이 있다(적재 DB에서 찾음)
        String key = "개인정보보호법|37-2|1||";
        List<LawUnits.Unit> vs = snap.laws().versions(key);
        LawUnits.Unit pending = vs.stream().filter(u -> LawUnits.PENDING.equals(u.status())).findFirst().orElse(null);
        assumeThat(pending).as("시행 예정 판 없음").isNotNull();
        var p = snap.packages().current("PKG10").orElseThrow();
        PackageOpener.IssueRef ref = new PackageOpener.IssueRef(1, p.issueName(), "PKG10", List.copyOf(p.conditionIds()),
                List.of(), List.of());
        LocalDate before = pending.effectiveFrom().minusDays(1);
        PackageOpener.Source s = opener.open("v2", List.of(ref), before).sources().get(key);
        assertThat(s).isNotNull();
        assertThat(s.upcoming()).extracting(PackageOpener.Upcoming::effectiveFrom).contains(pending.effectiveFrom());
        assertThat(s.textHash()).isNotEqualTo(pending.textHash());
        PackageOpener.Source after = opener.open("v2", List.of(ref), pending.effectiveFrom()).sources().get(key);
        assertThat(after.textHash()).isEqualTo(pending.textHash());
        assertThat(after.upcoming()).isEmpty();
        System.out.println("S8 A4 시행 예정: " + key + " " + before + " 판 → 예정 " + s.upcoming());
    }

    @Test
    void pastReferenceDateTransfers() {
        LocalDate today = LocalDate.of(2026, 11, 27);
        PackageOpener.RefDate past = PackageOpener.referenceDate(LocalDate.of(2026, 3, 1), "INQUIRY", today);
        assertThat(past.past()).isTrue();
        assertThat(past.openDate()).isEqualTo(today);
        PackageOpener.RefDate future = PackageOpener.referenceDate(LocalDate.of(2026, 12, 1), "INQUIRY", today);
        assertThat(future.past()).isFalse();
        assertThat(future.openDate()).isEqualTo(LocalDate.of(2026, 12, 1));
        PackageOpener.RefDate none = PackageOpener.referenceDate(null, null, today);
        assertThat(none.fromToday()).isTrue();
        assertThat(none.openDate()).isEqualTo(today);
    }

    @Test
    void g2ListIs257Articles() {
        ArticleList l = new ArticleList(snap, LocalDate.of(2026, 11, 27));
        System.out.printf("S8 G2 목록: %d조, %d자%n", l.size(), l.text().length());
        assertThat(l.size()).isEqualTo(257);
        assertThat(l.has("개인정보보호법|15|")).isTrue();
        assertThat(l.has("개인정보보호법|71|")).as("안B 밖(벌칙)").isFalse();
        assertThat(l.units("개인정보보호법|15|")).isNotEmpty();
        assertThat(l.coreOf("개인정보보호법|15|")).extracting(p -> p.packageId()).contains("PKG01");
    }
}
