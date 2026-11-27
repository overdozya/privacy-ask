package io.github.overdozya.privacyask.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 06 7절 S4 ①②: 스냅샷이 적재 전체를 읽고(행 수 = manifest), 조회가 맞는지. 실제 적재 파일로 돈다. */
class KnowledgeSnapshotTest {
    static KnowledgeSnapshot s;

    @BeforeAll
    static void load() {
        assumeThat(LoadedDb.available()).as("적재 파일 또는 Docker 없음").isTrue();
        s = LoadedDb.snapshot();
    }

    @Test
    void readsEveryTableAndRowCountsEqualManifest() {
        Map<String, Long> manifest = Knowledge.readManifest(LoadedDb.MYSQL_DIR.resolve("manifest.json"), LoadedDb.JSON);
        assertThat(s.stats().rows()).containsExactlyInAnyOrderEntriesOf(manifest);
        assertThat(s.stats().totalRows()).isEqualTo(manifest.values().stream().mapToLong(Long::longValue).sum());
        assertThat(KnowledgeCheck.check(s, manifest)).isEmpty();
        System.out.printf("스냅샷: %d행, %d ms, 힙 %d → %d MB%n", s.stats().totalRows(), s.stats().loadMs(),
                s.stats().heapBeforeBytes() >> 20, s.stats().heapAfterBytes() >> 20);
    }

    @Test
    void lawUnitByKeyAndDateIncludesUpcoming() {
        String key = "개인정보보호법|32-2|1||";
        LawUnits.At before = s.laws().at(key, LocalDate.of(2026, 12, 1));
        assertThat(before.inForce().chunkId()).isEqualTo(226);
        assertThat(before.inForce().status()).isEqualTo("CURRENT");
        assertThat(before.upcoming()).extracting(LawUnits.Unit::chunkId).containsExactly(480);
        assertThat(before.upcoming().get(0).effectiveFrom()).isEqualTo(LocalDate.of(2027, 7, 1));
        // 시행일 당일부터 예정본이 시행 중, 하루 전까지는 현행
        assertThat(s.laws().inForce(key, LocalDate.of(2027, 6, 30)).orElseThrow().chunkId()).isEqualTo(226);
        LawUnits.At after = s.laws().at(key, LocalDate.of(2027, 7, 1));
        assertThat(after.inForce().chunkId()).isEqualTo(480);
        assertThat(after.upcoming()).isEmpty();
        // 시행 전 날짜: 현행 시행일(2026-09-11) 앞이면 판 없음
        assertThat(s.laws().inForce(key, LocalDate.of(2026, 9, 10))).isEmpty();
    }

    @Test
    void unitWhoseKeyDisappearsEndsOnEffectiveTo() {
        String key = "근로기준법|101|1||";
        assertThat(s.laws().inForce(key, LocalDate.of(2026, 12, 7)).orElseThrow().chunkId()).isEqualTo(8248);
        assertThat(s.laws().inForce(key, LocalDate.of(2026, 12, 8))).isEmpty();
        // ARCHIVED(삭제 단위)는 어느 날에도 시행 중이 아님
        assertThat(s.laws().versions("개인정보보호법|7|3||")).allMatch(u -> u.status().equals("ARCHIVED"));
        assertThat(s.laws().inForce("개인정보보호법|7|3||", LocalDate.of(2019, 1, 1))).isEmpty();
    }

    @Test
    void articlePrefixAndHashLookups() {
        List<LawUnits.Unit> art15 = s.laws().inForceByPrefix("개인정보보호법|15|", LocalDate.of(2026, 12, 1));
        assertThat(art15).isNotEmpty().allMatch(u -> u.unitKey().startsWith("개인정보보호법|15|"));
        assertThat(art15).extracting(LawUnits.Unit::unitKey).doesNotHaveDuplicates()
                .noneMatch(k -> k.startsWith("개인정보보호법|15-"));
        LawUnits.Unit u = s.laws().byChunkId(226).orElseThrow();
        assertThat(s.laws().hashMatches(u.unitKey(), u.textHash())).isTrue();
        assertThat(s.laws().hashMatches(u.unitKey(), "0".repeat(64))).isFalse();
        assertThat(Hashes.sha256(u.text())).isEqualTo(u.textHash());
    }

    @Test
    void currentPackagesAndItems() {
        assertThat(s.packages().currentVersion()).isEqualTo("v2");
        assertThat(s.packages().versions()).containsExactly("v1", "v2");
        assertThat(s.packages().current()).hasSize(14).allMatch(Packages.Package::current);
        Packages.Package pkg13 = s.packages().current("PKG13").orElseThrow();
        assertThat(pkg13.issueName()).isEqualTo("광고성 정보 전송");
        assertThat(pkg13.interpIssueIds()).contains("I26");
        assertThat(pkg13.ruleIds()).containsExactly("R-A01", "R-A02");
        assertThat(s.packages().byIssue("v2", "가명정보 결합").orElseThrow().packageId()).isEqualTo("PKG09");
        int items = s.packages().current().stream().mapToInt(p -> p.items().size()).sum();
        assertThat(items).isPositive();
        assertThat(s.packages().current().stream().flatMap(p -> p.items().stream()))
                .allMatch(it -> it.version().equals("v2"));
    }

    @Test
    void serviceToLedgerCandidates() {
        Ledger.ServiceMatch m = s.ledger().forService("T 멤버십");
        assertThat(m.kind()).isEqualTo(Ledger.MatchKind.EXACT);
        assertThat(m.key()).isEqualTo("T멤버십");
        assertThat(m.rows()).extracting(Ledger.Row::rowId).contains("첨부1-0039", "첨부1-0040", "첨부1-0041", "첨부1-0042");
        assertThat(m.rows()).allMatch(r -> r.serviceKeys().contains("T멤버십"));  // 'T멤버십전자상거래'는 빠짐
        // 첨부 제한(L1 = 첨부1·2)
        assertThat(s.ledger().forService("T 멤버십", List.of("첨부1", "첨부2")).rows())
                .allMatch(r -> List.of("첨부1", "첨부2").contains(r.attachment()));
        // 줄바꿈 공백이 들어간 이름도 같은 키
        assertThat(s.ledger().forService("T멤 버십").rows()).isEqualTo(m.rows());
        assertThat(s.ledger().forService("없는 서비스 이름 ZZZ").kind()).isEqualTo(Ledger.MatchKind.NONE);
        Ledger.Row r = s.ledger().row("첨부1-0040").orElseThrow();
        assertThat(r.textHash()).isEqualTo("e06651da80b69e4fa9b71b05b96895d032badbfb1926fba90d7d147976ec7762");
        assertThat(r.field("수집ㆍ이용 목적").orElseThrow()).contains("멤버십 관련 고지사항 전달");
        assertThat(s.ledger().latestVersion()).contains("20261028");
    }

    @Test
    void catalogRulesAndSlotRules() {
        assertThat(s.catalog().facts()).hasSize(46);
        assertThat(s.catalog().optionCount()).isEqualTo(162);
        assertThat(s.catalog().slots()).hasSize(8);
        assertThat(s.catalog().fact("F-C2").orElseThrow().hasOption("잘 모르겠음")).isTrue();
        assertThat(s.ruleSet().version()).isEqualTo("v1.0");
        assertThat(s.ruleSet().rules()).hasSize(16);
        assertThat(s.ruleSet().exitFor("L")).contains("ANSWER");
        assertThat(s.ruleSet().exitFor("H")).contains("TRANSFER");
        assertThat(s.slotRules().size()).isEqualTo(52);
        assertThat(s.slotRules().bySlot().keySet()).hasSize(8);
        assertThat(s.slotRules().forSlot("night_transmission")).anyMatch(r -> r.effect().equals("SKIP"));
        var v = s.versions();
        assertThat(v.factMapSha256()).isEqualTo(s.catalog().factMapSha256());
        assertThat(v.packageContentSha256()).hasSize(14);
    }

    @Test
    void guideSectionHashCoversSubtree() {
        // 안내서 절 하나(꾸러미가 가리키는 절): 해시 = 하위 포함 청크 text_hash를 chunk_id 순으로 이은 sha256
        String id = "G13-0e24daddf9";
        List<Integer> ids = s.sections().chunkIds(id);
        assertThat(ids).isNotEmpty().isSorted();
        String joined = String.join("\n", ids.stream().map(c -> s.laws().byChunkId(c).orElseThrow().textHash()).toList());
        assertThat(s.sections().hash(id)).contains(Hashes.sha256(joined));
        assertThat(s.sections().chunkIds(id)).containsAll(s.sections().ownChunkIds(id));
    }
}
