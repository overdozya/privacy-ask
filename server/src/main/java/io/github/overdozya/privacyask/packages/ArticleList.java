package io.github.overdozya.privacyask.packages;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.github.overdozya.privacyask.knowledge.Documents;
import io.github.overdozya.privacyask.knowledge.KnowledgeSnapshot;
import io.github.overdozya.privacyask.knowledge.LawUnits;
import io.github.overdozya.privacyask.knowledge.Packages;

/**
 * 일반 문의 G2 목록(04 결정 12, 4.2 표 G2): 안B = 법령 문서 중 use_policy ANSWER_BASIS인 것의 use_scope 접두에 드는 조(기준일에 시행 중인
 * 단위가 하나라도 있는 '법|조|'), 줄마다 '조 키 + 조 제목'. DB(스냅샷)에서 만든다. 2단계 결과 기준 257조(1~6 안B 227 + 고시 16·17 25 +
 * 보존기간 단일 조 5).
 * 조 원문 = 그 조의 기준일 판 단위 전부(항 순). 고른 조가 어떤 쟁점 꾸러미(지금 판)의 '핵심' 조문이면 그 꾸러미의 안내서 절(조건 없는 항목)도
 * 붙인다.
 */
public final class ArticleList {

    /** 한 줄. key = '개인정보보호법|15|', label = '제15조', title = 조 제목. */
    public record Entry(String key, int docId, String lawName, String label, String title) {
        public String line() {
            return key + " " + label + (title == null || title.isBlank() ? "" : "(" + title + ")");
        }
    }

    private final KnowledgeSnapshot k;
    private final LocalDate date;
    private final List<Entry> entries;
    private final Map<String, Entry> byKey;

    public ArticleList(KnowledgeSnapshot k, LocalDate date) {
        this.k = k;
        this.date = date;
        List<Entry> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<Documents.Doc> docs = k.documents().all().stream()
                .filter(d -> d.isLaw() && "ANSWER_BASIS".equals(d.usePolicy()))
                .sorted(java.util.Comparator.comparingInt(Documents.Doc::docId)).toList();
        for (Documents.Doc d : docs) {
            for (String prefix : d.useScope() == null ? List.<String>of() : d.useScope()) {
                for (LawUnits.Unit u : k.laws().inForceByPrefix(prefix, date)) {
                    if (u.docId() != d.docId()) {
                        continue;
                    }
                    String a = PackageOpener.articlePrefix(u.unitKey());
                    if (seen.add(a)) {
                        out.add(new Entry(a, d.docId(), d.lawName(), articleLabel(u.article()), u.title()));
                    }
                }
            }
        }
        // 같은 문서 안에서는 조 번호 순(쓰는 범위 접두 순과 키 순이 다를 수 있음: '…|2|' 뒤 '…|20|')
        this.entries = List.copyOf(out);
        Map<String, Entry> m = new LinkedHashMap<>();
        out.forEach(e -> m.put(e.key(), e));
        this.byKey = java.util.Collections.unmodifiableMap(m);
    }

    /** '제2조 제1호' → '제2조'. */
    static String articleLabel(String article) {
        if (article == null) {
            return "";
        }
        int sp = article.indexOf(' ');
        return sp < 0 ? article : article.substring(0, sp);
    }

    public LocalDate date() {
        return date;
    }

    public List<Entry> entries() {
        return entries;
    }

    public int size() {
        return entries.size();
    }

    public boolean has(String key) {
        return byKey.containsKey(key);
    }

    public Optional<Entry> get(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    /** 모델에 넘기는 글(줄마다 한 조). */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            sb.append(e.line()).append('\n');
        }
        return sb.toString();
    }

    /** 조 원문: 그 조의 기준일 판 단위 전부(키 순). */
    public List<LawUnits.Unit> units(String key) {
        Entry e = byKey.get(key);
        if (e == null) {
            return List.of();
        }
        return k.laws().inForceByPrefix(key, date).stream().filter(u -> u.docId() == e.docId()).toList();
    }

    /** 고른 조를 '핵심' 조문으로 둔 꾸러미(지금 판, package_id 순). */
    public List<Packages.Package> coreOf(String key) {
        List<Packages.Package> out = new ArrayList<>();
        for (Packages.Package p : k.packages().current()) {
            boolean core = p.items().stream().anyMatch(it -> "핵심".equals(it.role())
                    && PackageOpener.LAW_UNIT.equals(it.targetKind())
                    && PackageOpener.articlePrefix(it.targetKey()).equals(key));
            if (core) {
                out.add(p);
            }
        }
        return out;
    }
}
