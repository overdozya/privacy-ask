package io.github.overdozya.privacyask.packages;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.github.overdozya.privacyask.knowledge.Annexes;
import io.github.overdozya.privacyask.knowledge.Documents;
import io.github.overdozya.privacyask.knowledge.GuideSections;
import io.github.overdozya.privacyask.knowledge.KbLinks;
import io.github.overdozya.privacyask.knowledge.KnowledgeSnapshot;
import io.github.overdozya.privacyask.knowledge.LawUnits;
import io.github.overdozya.privacyask.knowledge.Packages;

/**
 * A4 꾸러미 열기(03 4.2 A4, 06 1.5 패키지 14). 코드만, LLM 없음, 스냅샷(메모리)만 읽는다.
 *
 * <ul>
 *   <li>쟁점마다 그 꾸러미(사안이 고정한 판)의 항목 중 conds가 없거나 맞은 조건과 겹치는 것만 연다. 하위 꾸러미(PKG14)는 부모 쟁점 아래에서
 *       하위 꾸러미의 맞은 조건으로 같은 규칙.</li>
 *   <li>원문 단위: 조문(LAW_UNIT) = 단위 키의 기준일 판(현행·시행 예정 중 기준일에 시행 중인 것), 별표 = 별표 키의 기준일 판, 안내서 절 =
 *       항목 단위(청크) 글을 이은 것 + 절 해시(C13과 같은 계산). 적재 때의 chunk_id가 아니라 키로 다시 고른다(옛 판이 섞이지 않게).</li>
 *   <li>연결 한 단계: 연 조문의 기준일 판 청크에서 kb_links 위임·별표(정확·상위단위)만 따라가 꾸러미에 없는 조문·별표를 붙인다. 붙인 것에서 다시
 *       따라가지 않는다. 본문참조·준용은 따르지 않는다(03 A4 "위임 조문·별표", 크기).</li>
 *   <li>기준일 뒤 시행 예정 판이 있으면 그 단위에 upcoming(시행일·해시)을 단다.</li>
 * </ul>
 * 기준일을 정하는 것(오늘 / 사실의 처리 시작일, 과거면 이관)은 {@link #referenceDate}.
 */
public final class PackageOpener {
    public static final String LAW_UNIT = "LAW_UNIT", ANNEX = "ANNEX", GUIDE_SECTION = "GUIDE_SECTION";
    public static final String ROLE_LINKED = "연결";
    static final Set<String> LINK_TYPES = Set.of("위임", "별표");
    static final Set<String> LINK_RESOLVED = Set.of("정확", "상위단위");

    /** 쟁점 하나(S5 IssueDeriver.Issue에서): 맞은 조건·켜진 해석 쟁점·하위 꾸러미. flow는 흐름 번호(일반 문의 조 고르기면 null). */
    public record IssueRef(Integer flow, String issueName, String packageId, List<String> conditionIds,
                           List<String> interpOn, List<SubRef> subs) {
        public IssueRef {
            conditionIds = List.copyOf(conditionIds);
            interpOn = List.copyOf(interpOn);
            subs = List.copyOf(subs);
        }
    }

    public record SubRef(String packageId, List<String> conditionIds, List<String> interpOn) {
        public SubRef {
            conditionIds = List.copyOf(conditionIds);
            interpOn = List.copyOf(interpOn);
        }
    }

    /** 기준일 뒤 시행 예정 판. */
    public record Upcoming(LocalDate effectiveFrom, String textHash) {}

    /**
     * 원문 단위 하나(인용 키 = key). label: 조문이면 '제17조 제1항', 별표면 별표 이름, 절이면 절 경로. text = 기준일 판 원문.
     * usePolicy: 문서 사용 정책(ANSWER_BASIS·REFERENCE·OUT). answerBasis: 꾸러미 항목의 즉답 근거 표시(연결로 붙은 것은 문서 정책이
     * ANSWER_BASIS일 때만 참).
     */
    public record Source(String key, String kind, int docId, String docTitle, String label, String title, String text,
                         String textHash, LocalDate effectiveFrom, String status, List<Upcoming> upcoming,
                         String usePolicy, boolean answerBasis) {
        public boolean hasUpcoming() {
            return !upcoming.isEmpty();
        }
    }

    /** 연 항목(꾸러미 v2 package_items 한 행) + 그 항목의 원문 키. */
    public record OpenedItem(int itemId, String packageId, int ord, String targetKind, String targetKey, String role,
                             List<String> conds, boolean answerBasis, List<String> keys) {}

    /** 연결로 붙은 단위: 어느 키에서 어떤 연결로. */
    public record Linked(String fromKey, String linkType, String key) {}

    /**
     * 쟁점 하나의 꾸러미. keys = 이 쟁점에 넘기는 원문 키 전부(항목 순, 연결은 뒤). roles = 키 → 역할(같은 키가 둘 이상 항목에 있으면
     * 앞 항목). missing = 기준일에 시행 중인 판이 없는 키.
     */
    public record OpenedIssue(Integer flow, String issueName, String packageId, List<String> conditionIds,
                              List<String> interpOn, List<OpenedItem> items, List<Linked> linked, List<String> keys,
                              Map<String, String> roles, List<String> missing) {}

    /** 연 결과. sources = 키 → 원문(쟁점 사이에 겹치면 한 번). chars = sources 원문 글자 수 합. */
    public record Opened(String packageVersion, LocalDate date, List<OpenedIssue> issues, Map<String, Source> sources,
                         int chars, long micros) {
        public boolean anyUpcoming() {
            return sources.values().stream().anyMatch(Source::hasUpcoming);
        }

        public Set<String> keys() {
            return sources.keySet();
        }
    }

    /**
     * 기준일(03 4.2 A4): 사안 기준일(사실 F-S3에서 A1이 cases.reference_date로 옮김)이 있으면 그날, 없으면 오늘. 오늘보다 앞이면
     * past = true(과거 시점 사안 → 이관 사유), 원문은 오늘 판으로 연다(과거본은 받지 않음).
     */
    public record RefDate(LocalDate caseDate, String basis, LocalDate openDate, boolean past, boolean fromToday) {}

    public static RefDate referenceDate(LocalDate caseDate, String basis, LocalDate today) {
        if (caseDate == null) {
            return new RefDate(null, null, today, false, true);
        }
        if (caseDate.isBefore(today)) {
            return new RefDate(caseDate, basis, today, true, false);
        }
        return new RefDate(caseDate, basis, caseDate, false, false);
    }

    private final KnowledgeSnapshot k;

    public PackageOpener(KnowledgeSnapshot k) {
        this.k = k;
    }

    public Opened open(String packageVersion, Collection<IssueRef> issues, LocalDate date) {
        long t0 = System.nanoTime();
        Map<String, Source> sources = new LinkedHashMap<>();
        List<OpenedIssue> out = new ArrayList<>();
        for (IssueRef ref : issues) {
            Packages.Package p = k.packages().get(packageVersion, ref.packageId())
                    .orElseThrow(() -> new IllegalArgumentException("꾸러미 없음: " + packageVersion + " " + ref.packageId()));
            List<OpenedItem> items = new ArrayList<>();
            LinkedHashSet<String> keys = new LinkedHashSet<>();
            Map<String, String> roles = new LinkedHashMap<>();
            List<String> missing = new ArrayList<>();
            addItems(p, ref.conditionIds(), date, items, keys, roles, missing, sources);
            for (SubRef s : ref.subs()) {
                Packages.Package sp = k.packages().get(packageVersion, s.packageId())
                        .orElseThrow(() -> new IllegalArgumentException("하위 꾸러미 없음: " + s.packageId()));
                addItems(sp, s.conditionIds(), date, items, keys, roles, missing, sources);
            }
            List<Linked> linked = new ArrayList<>();
            for (String key : List.copyOf(keys)) {
                Source src = sources.get(key);
                if (src == null || !LAW_UNIT.equals(src.kind())) {
                    continue;
                }
                for (Linked l : follow(key, date)) {
                    if (keys.contains(l.key()) || !put(l.key(), date, sources)) {
                        continue;
                    }
                    keys.add(l.key());
                    roles.putIfAbsent(l.key(), ROLE_LINKED);
                    linked.add(l);
                }
            }
            out.add(new OpenedIssue(ref.flow(), ref.issueName(), ref.packageId(), ref.conditionIds(), ref.interpOn(),
                    List.copyOf(items), List.copyOf(linked), List.copyOf(keys), Map.copyOf(roles), List.copyOf(missing)));
        }
        int chars = sources.values().stream().mapToInt(s -> s.text().length()).sum();
        return new Opened(packageVersion, date, List.copyOf(out), java.util.Collections.unmodifiableMap(sources), chars,
                (System.nanoTime() - t0) / 1000);
    }

    /** 항목 고르기: conds가 없거나 맞은 조건과 겹침. */
    static boolean applies(Packages.Item it, Collection<String> matched) {
        return it.conds() == null || it.conds().isEmpty() || it.conds().stream().anyMatch(matched::contains);
    }

    private void addItems(Packages.Package p, List<String> matched, LocalDate date, List<OpenedItem> items,
                          Set<String> keys, Map<String, String> roles, List<String> missing,
                          Map<String, Source> sources) {
        for (Packages.Item it : p.items()) {
            if (!applies(it, matched)) {
                continue;
            }
            List<String> itemKeys = new ArrayList<>();
            if (GUIDE_SECTION.equals(it.targetKind())) {
                String key = it.targetKey();
                if (sources.containsKey(key) || putGuide(it, sources)) {
                    itemKeys.add(key);
                } else {
                    missing.add(key);
                }
            } else {
                for (Packages.Unit u : it.units()) {
                    String key = ANNEX.equals(it.targetKind()) ? annexKey(u) : u.unitKey();
                    if (key == null) {
                        continue;
                    }
                    if (sources.containsKey(key) || put(key, date, sources, it)) {
                        itemKeys.add(key);
                    } else if (!missing.contains(key)) {
                        missing.add(key);
                    }
                }
            }
            for (String key : itemKeys) {
                keys.add(key);
                roles.putIfAbsent(key, it.role());
            }
            items.add(new OpenedItem(it.itemId(), it.packageId(), it.ord(), it.targetKind(), it.targetKey(), it.role(),
                    it.conds() == null ? List.of() : List.copyOf(it.conds()), it.answerBasis(), List.copyOf(itemKeys)));
        }
    }

    private String annexKey(Packages.Unit u) {
        if (u.annexId() != null) {
            return k.annexes().byId(u.annexId()).map(Annexes.Annex::annexKey).orElse(u.unitKey());
        }
        return u.unitKey();
    }

    /** 연결로 붙는 키(위임·별표 한 단계). 조 단위 목적지('…|28|||')는 그 조의 기준일 판 단위 키 전부. */
    List<Linked> follow(String key, LocalDate date) {
        Optional<LawUnits.Unit> u = k.laws().inForce(key, date);
        if (u.isEmpty()) {
            return List.of();
        }
        List<Linked> out = new ArrayList<>();
        for (KbLinks.Link l : k.links().from(u.get().chunkId())) {
            if (!LINK_TYPES.contains(l.linkType()) || !LINK_RESOLVED.contains(l.resolution())) {
                continue;
            }
            if (l.dstAnnexId() != null) {
                k.annexes().byId(l.dstAnnexId()).ifPresent(a -> out.add(new Linked(key, l.linkType(), a.annexKey())));
                continue;
            }
            if (l.dstChunkId() == null) {
                continue;
            }
            String dst = l.dstKey();
            if (dst != null && k.laws().has(dst)) {
                out.add(new Linked(key, l.linkType(), dst));
                continue;
            }
            LawUnits.Unit d = k.laws().byChunkId(l.dstChunkId()).orElse(null);
            if (d == null) {
                continue;
            }
            String prefix = articlePrefix(dst != null ? dst : d.unitKey());
            for (LawUnits.Unit x : k.laws().inForceByPrefix(prefix, date)) {
                out.add(new Linked(key, l.linkType(), x.unitKey()));
            }
        }
        return out;
    }

    /** '법|조|항|호|' → '법|조|'. */
    public static String articlePrefix(String unitKey) {
        String[] p = unitKey.split("\\|", -1);
        return p.length < 2 ? unitKey : p[0] + "|" + p[1] + "|";
    }

    private boolean put(String key, LocalDate date, Map<String, Source> sources) {
        return put(key, date, sources, null);
    }

    /** 조문·별표 키의 기준일 판을 sources에 넣는다. 그날 시행 중인 판이 없으면 false. */
    private boolean put(String key, LocalDate date, Map<String, Source> sources, Packages.Item item) {
        if (sources.containsKey(key)) {
            return true;
        }
        LawUnits.At at = k.laws().at(key, date);
        if (at.inForce() != null) {
            LawUnits.Unit u = at.inForce();
            Documents.Doc d = k.documents().get(u.docId()).orElseThrow();
            List<Upcoming> up = at.upcoming().stream().map(x -> new Upcoming(x.effectiveFrom(), x.textHash())).toList();
            boolean basis = item != null ? item.answerBasis() : inScope(d, key);
            sources.put(key, new Source(key, LAW_UNIT, u.docId(), d.title(), u.article(), u.title(), u.text(),
                    u.textHash(), u.effectiveFrom(), u.status(), up, d.usePolicy(), basis));
            return true;
        }
        if (k.annexes().has(key)) {
            Annexes.At a = k.annexes().at(key, date);
            if (a.inForce() == null) {
                return false;
            }
            Annexes.Annex x = a.inForce();
            Documents.Doc d = k.documents().get(x.docId()).orElseThrow();
            List<Upcoming> up = a.upcoming().stream().map(y -> new Upcoming(y.effectiveFrom(), y.textHash())).toList();
            boolean basis = item != null ? item.answerBasis() : "ANSWER_BASIS".equals(d.usePolicy());
            sources.put(key, new Source(key, ANNEX, x.docId(), d.title(), x.label(), x.title(), x.text(), x.textHash(),
                    x.effectiveFrom(), x.status(), up, d.usePolicy(), basis));
            return true;
        }
        return false;
    }

    /** 연결로 붙은 조문: 문서가 ANSWER_BASIS이고 쓰는 범위(use_scope 접두)에 들면 즉답 근거. */
    static boolean inScope(Documents.Doc d, String key) {
        if (!"ANSWER_BASIS".equals(d.usePolicy())) {
            return false;
        }
        return d.useScope() == null || d.useScope().isEmpty() || d.useScope().stream().anyMatch(key::startsWith);
    }

    /** 안내서 절: 항목 단위(청크)의 글을 단위 순으로 이음. 해시 = 절 해시(C13). */
    private boolean putGuide(Packages.Item it, Map<String, Source> sources) {
        String id = it.targetKey();
        GuideSections.Section s = k.sections().get(id).orElse(null);
        if (s == null) {
            return false;
        }
        StringBuilder sb = new StringBuilder();
        for (Packages.Unit u : it.units()) {
            if (u.chunkId() == null) {
                continue;
            }
            k.laws().byChunkId(u.chunkId()).ifPresent(c -> {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(c.text());
            });
        }
        Documents.Doc d = k.documents().get(s.docId()).orElseThrow();
        String hash = k.sections().hash(id).orElse(null);
        sources.put(id, new Source(id, GUIDE_SECTION, s.docId(), d.title(), s.pathText(), s.title(), sb.toString(), hash,
                null, null, List.of(), d.usePolicy(), it.answerBasis()));
        return true;
    }
}
