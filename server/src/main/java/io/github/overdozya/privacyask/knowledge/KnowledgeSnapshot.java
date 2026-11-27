package io.github.overdozya.privacyask.knowledge;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 지식·기준표·규칙 전부를 기동 때 한 번 읽은 불변 객체(06 3.1, 6.2). 실행 중 지식 조회는 DB를 부르지 않는다.
 * 다시 읽는 것은 재기동 때뿐(적재는 서버를 내린 채 한다).
 */
public record KnowledgeSnapshot(Map<String, String> buildInfo, Documents documents, LawUnits laws, Annexes annexes,
                                GuideSections sections, KbLinks links, Packages packages, Ledger ledger,
                                Catalog catalog, RuleSet ruleSet, SlotRules slotRules, Stats stats) {

    /** rows: 테이블별 읽은 행 수. heap*: 적재 전후 힙 사용량(GC 뒤, 바이트). */
    public record Stats(Map<String, Long> rows, long loadMs, long heapBeforeBytes, long heapAfterBytes,
                        Instant loadedAt) {
        public long totalRows() {
            return rows.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    /** 평가 기록·GET /api/knowledge에 그대로 쓰는 판·해시(06 3.1, 5절 API 7). */
    public record Versions(String ruleSet, String ruleSetSha256, String factMap, String factMapSha256,
                           String slotRules, String slotRulesSha256, String packageCurrent,
                           Map<String, String> packageContentSha256, List<String> ledgerVersions,
                           Map<String, String> buildInfo) {}

    public Versions versions() {
        Map<String, String> pkg = new LinkedHashMap<>();
        packages.current().forEach(p -> pkg.put(p.packageId(), p.contentSha256()));
        Catalog.Source rs = catalog.source(Catalog.RULE_SET).orElseThrow();
        Catalog.Source fm = catalog.source(Catalog.FACT_MAP).orElseThrow();
        Catalog.Source sr = catalog.source(Catalog.SLOT_RULES).orElseThrow();
        return new Versions(rs.version(), rs.sha256(), fm.version(), fm.sha256(), sr.version(), sr.sha256(),
                packages.currentVersion(), pkg,
                ledger.versions().stream().map(Ledger.Version::versionId).distinct().sorted().toList(), buildInfo);
    }
}
