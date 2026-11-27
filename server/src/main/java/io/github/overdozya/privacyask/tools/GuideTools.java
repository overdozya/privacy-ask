package io.github.overdozya.privacyask.tools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.github.overdozya.privacyask.knowledge.Documents;
import io.github.overdozya.privacyask.knowledge.GuideSections;
import io.github.overdozya.privacyask.knowledge.Knowledge;
import io.github.overdozya.privacyask.knowledge.KnowledgeSnapshot;
import io.github.overdozya.privacyask.knowledge.LawUnits;

/**
 * A6 보조 검색 도구 두 개(03 4.2 A6, 06 1.5 패키지 17: GuideSearchTool·ReadSectionTool을 한 곳에). 부를지·검색어는 LLM(A5)이 정하고
 * 실행은 여기(코드)다. 법령은 찾지 않는다(안내서 청크만).
 * <ul>
 * <li>search_guides(질문, 쟁점): 벡터 코사인 상위 VEC_K + 글자 2-그램 BM25 상위 TEXT_K를 순위 융합(RRF, k=60)해 청크 점수를 내고,
 * 청크가 속한 절마다 가장 높은 청크 점수로 절 순위를 정한다. 질의 모델이나 벡터가 없으면 글자 검색만(mode TEXT_ONLY + 까닭).</li>
 * <li>read_section(절 ID): 그 절과 아래 절의 청크 글을 chunk_id 순으로 줄바꿈으로 잇고(꾸러미 절 원문과 같은 꼴), 해시는 스냅샷 절 해시
 * (A7 인용 검증 C13과 같은 계산). 글이 길면 앞 readMaxChars자만(truncated).</li>
 * </ul>
 */
public final class GuideTools {
    public static final String SEARCH = "search_guides", READ = "read_section";
    public static final String HYBRID = "HYBRID", TEXT_ONLY = "TEXT_ONLY";
    static final int VEC_K = 50, TEXT_K = 50, RRF_K = 60;

    public record Hit(String sectionId, int docId, String doc, String path, String title, double score,
                      Integer vectorRank, Integer textRank, int chunkId) {}

    /** mode HYBRID·TEXT_ONLY, note = 글자 검색만인 까닭. */
    public record SearchResult(String query, String mode, String note, List<Hit> hits, long embedMs, long searchMs) {}

    public record Section(String sectionId, int docId, String doc, String usePolicy, String path, String title,
                          String text, String textHash, int chars, boolean truncated) {}

    private final Knowledge knowledge;
    private final GuideIndex index;
    private final QueryEmbedder embedder;
    private final ToolsProperties props;

    public GuideTools(Knowledge knowledge, GuideIndex index, QueryEmbedder embedder, ToolsProperties props) {
        this.knowledge = knowledge;
        this.index = index;
        this.embedder = embedder;
        this.props = props;
    }

    public boolean enabled() {
        return props.enabled() && index != null && index.size() > 0;
    }

    public ToolsProperties props() {
        return props;
    }

    public GuideIndex index() {
        return index;
    }

    public QueryEmbedder embedder() {
        return embedder;
    }

    /** 질문(+쟁점 이름)으로 안내서 절 후보 상위 limit. exclude = 이미 글을 준 절(빼고 센다). */
    public SearchResult search(String query, String issue, int limit, Set<String> exclude) {
        String q = issue == null || issue.isBlank() ? query : issue + ": " + query;
        long t0 = System.nanoTime();
        String mode = HYBRID, note = null;
        List<GuideIndex.Ranked> vec = List.of();
        long embedMs = 0;
        if (index.vectors() == 0) {
            mode = TEXT_ONLY;
            note = "안내서 벡터 없음(15_kb_embeddings.sql 적재 전)";
        } else if (!embedder.available()) {
            mode = TEXT_ONLY;
            note = embedder.why();
        } else {
            try {
                long e0 = System.nanoTime();
                float[] v = embedder.embed(q);
                embedMs = (System.nanoTime() - e0) / 1_000_000;
                vec = index.nearest(v, VEC_K);
            } catch (RuntimeException e) {
                mode = TEXT_ONLY;
                note = "질의 임베딩 실패: " + e.getMessage();
            }
        }
        List<GuideIndex.Ranked> txt = index.textSearch(q, TEXT_K);
        Map<Integer, double[]> fused = new LinkedHashMap<>();   // 청크 → [점수, 벡터 순위, 글자 순위]
        for (int r = 0; r < vec.size(); r++) {
            double[] f = fused.computeIfAbsent(vec.get(r).chunk(), x -> new double[] {0, -1, -1});
            f[0] += 1.0 / (RRF_K + r + 1);
            f[1] = r + 1;
        }
        for (int r = 0; r < txt.size(); r++) {
            double[] f = fused.computeIfAbsent(txt.get(r).chunk(), x -> new double[] {0, -1, -1});
            f[0] += 1.0 / (RRF_K + r + 1);
            f[2] = r + 1;
        }
        Map<String, Hit> best = new LinkedHashMap<>();
        KnowledgeSnapshot k = knowledge.snapshot();
        for (var e : fused.entrySet()) {
            GuideIndex.Chunk c = index.chunk(e.getKey());
            if (exclude != null && exclude.contains(c.sectionId())) {
                continue;
            }
            double[] f = e.getValue();
            Hit old = best.get(c.sectionId());
            if (old == null || f[0] > old.score() || (f[0] == old.score() && c.chunkId() < old.chunkId())) {
                GuideSections.Section s = k.sections().get(c.sectionId()).orElse(null);
                if (s == null) {
                    continue;
                }
                String doc = k.documents().get(s.docId()).map(Documents.Doc::title).orElse(null);
                best.put(c.sectionId(), new Hit(c.sectionId(), s.docId(), doc, s.pathText(), s.title(), round(f[0]),
                        f[1] < 0 ? null : (int) f[1], f[2] < 0 ? null : (int) f[2], c.chunkId()));
            }
        }
        List<Hit> hits = new ArrayList<>(best.values());
        hits.sort(Comparator.comparingDouble(Hit::score).reversed().thenComparing(Hit::sectionId));
        // 검색 ms = 전체 - 질의 임베딩(벡터 내적 + 글자 검색 + 융합)
        long searchMs = Math.max(0, (System.nanoTime() - t0) / 1_000_000 - embedMs);
        return new SearchResult(q, mode, note, List.copyOf(hits.subList(0, Math.min(limit, hits.size()))), embedMs,
                searchMs);
    }

    static double round(double x) {
        return Math.round(x * 1e6) / 1e6;
    }

    /** 절 전체 글(코드가 붙임). 없는 절이면 빈 값. */
    public Optional<Section> read(String sectionId) {
        KnowledgeSnapshot k = knowledge.snapshot();
        GuideSections.Section s = k.sections().get(sectionId).orElse(null);
        if (s == null) {
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder();
        for (int cid : k.sections().chunkIds(sectionId)) {
            LawUnits.Unit u = k.laws().byChunkId(cid).orElse(null);
            if (u == null) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(u.text());
        }
        String full = sb.toString();
        boolean cut = full.length() > props.readMaxChars();
        Documents.Doc d = k.documents().get(s.docId()).orElse(null);
        return Optional.of(new Section(sectionId, s.docId(), d == null ? null : d.title(), d == null ? null : d.usePolicy(),
                s.pathText(), s.title(), cut ? full.substring(0, props.readMaxChars()) : full,
                k.sections().hash(sectionId).orElse(null), full.length(), cut));
    }
}
