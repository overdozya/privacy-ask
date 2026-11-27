package io.github.overdozya.privacyask.tools;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.overdozya.privacyask.knowledge.Documents;
import io.github.overdozya.privacyask.knowledge.KnowledgeSnapshot;
import io.github.overdozya.privacyask.knowledge.LawUnits;

/**
 * A6 검색 대상: 지식 스냅샷의 안내서 청크(현행, 절이 있는 것)와 그 벡터(kb_chunks.embedding, float32 LE 1024차원, 적재 파일 15_kb_embeddings.sql),
 * 글자 2-그램 색인. 기동 때 한 번 만든다(스냅샷처럼 메모리). 벡터가 하나도 없으면 vectors() 0 → 글자 검색만.
 */
public final class GuideIndex {
    public record Chunk(int chunkId, int docId, String sectionId, String text) {}

    /** 기동 때 잰 것. */
    public record Stats(int chunks, int vectors, int missingVectors, int textTerms, long loadMs, long heapBeforeBytes,
                        long heapAfterBytes) {}

    private final List<Chunk> chunks;
    private final float[] matrix;      // vectors × DIM
    private final int[] vecChunk;      // 벡터 행 → chunks 번호
    private final TextIndex text;
    private final Stats stats;

    GuideIndex(List<Chunk> chunks, float[] matrix, int[] vecChunk, TextIndex text, Stats stats) {
        this.chunks = List.copyOf(chunks);
        this.matrix = matrix;
        this.vecChunk = vecChunk;
        this.text = text;
        this.stats = stats;
    }

    /** 스냅샷 안내서 청크 + DB 벡터. */
    public static GuideIndex load(KnowledgeSnapshot k, JdbcClient jdbc) {
        Runtime rt = Runtime.getRuntime();
        long heap0 = rt.totalMemory() - rt.freeMemory();
        long t = System.nanoTime();
        List<Chunk> cs = new ArrayList<>();
        for (LawUnits.Unit u : k.laws().all()) {
            Documents.Doc d = k.documents().get(u.docId()).orElse(null);
            if (d == null || d.isLaw() || !u.isLive() || u.sectionId() == null) {
                continue;
            }
            cs.add(new Chunk(u.chunkId(), u.docId(), u.sectionId(), u.text()));
        }
        cs.sort(Comparator.comparingInt(Chunk::chunkId));
        Map<Integer, Integer> pos = new HashMap<>();
        for (int i = 0; i < cs.size(); i++) {
            pos.put(cs.get(i).chunkId(), i);
        }
        List<float[]> vs = new ArrayList<>();
        List<Integer> vc = new ArrayList<>();
        jdbc.sql("""
                SELECT c.chunk_id, c.embedding FROM kb_chunks c JOIN kb_documents d USING(doc_id)
                WHERE d.source_type = 'GUIDE' AND c.embedding IS NOT NULL ORDER BY c.chunk_id""")
                .query((rs, n) -> {
                    Integer i = pos.get(rs.getInt(1));
                    byte[] b = rs.getBytes(2);
                    if (i != null && b != null && b.length == QueryEmbedder.DIM * 4) {
                        float[] v = new float[QueryEmbedder.DIM];
                        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(v);
                        vs.add(v);
                        vc.add(i);
                    }
                    return null;
                }).list();
        float[] m = new float[vs.size() * QueryEmbedder.DIM];
        for (int i = 0; i < vs.size(); i++) {
            System.arraycopy(vs.get(i), 0, m, i * QueryEmbedder.DIM, QueryEmbedder.DIM);
        }
        int[] vcs = vc.stream().mapToInt(Integer::intValue).toArray();
        TextIndex ti = new TextIndex(cs.stream().map(Chunk::text).toList());
        long ms = (System.nanoTime() - t) / 1_000_000;
        long heap1 = rt.totalMemory() - rt.freeMemory();
        return new GuideIndex(cs, m, vcs, ti, new Stats(cs.size(), vcs.length, cs.size() - vcs.length, ti.terms(), ms,
                heap0, heap1));
    }

    /** 테스트용: 청크와 벡터(청크 순, null이면 벡터 없음)를 바로 받는다. */
    public static GuideIndex of(List<Chunk> chunks, List<float[]> vectors) {
        List<Integer> vc = new ArrayList<>();
        List<float[]> vs = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            if (vectors != null && vectors.get(i) != null) {
                vc.add(i);
                vs.add(vectors.get(i));
            }
        }
        float[] m = new float[vs.size() * QueryEmbedder.DIM];
        for (int i = 0; i < vs.size(); i++) {
            System.arraycopy(vs.get(i), 0, m, i * QueryEmbedder.DIM, QueryEmbedder.DIM);
        }
        TextIndex ti = new TextIndex(chunks.stream().map(Chunk::text).toList());
        return new GuideIndex(chunks, m, vc.stream().mapToInt(Integer::intValue).toArray(), ti,
                new Stats(chunks.size(), vs.size(), chunks.size() - vs.size(), ti.terms(), 0, 0, 0));
    }

    public record Ranked(int chunk, double score) {}

    /** 코사인(둘 다 길이 1이라 내적) 상위 n. */
    public List<Ranked> nearest(float[] q, int n) {
        int rows = vecChunk.length;
        double[] s = new double[rows];
        for (int r = 0; r < rows; r++) {
            int base = r * QueryEmbedder.DIM;
            double dot = 0;
            for (int d = 0; d < QueryEmbedder.DIM; d++) {
                dot += matrix[base + d] * q[d];
            }
            s[r] = dot;
        }
        List<Ranked> out = new ArrayList<>(rows);
        for (int r = 0; r < rows; r++) {
            out.add(new Ranked(vecChunk[r], s[r]));
        }
        out.sort((a, b) -> a.score() == b.score() ? Integer.compare(a.chunk(), b.chunk()) : Double.compare(b.score(), a.score()));
        return out.subList(0, Math.min(n, out.size()));
    }

    public List<Ranked> textSearch(String query, int n) {
        return text.search(query, n).stream().map(x -> new Ranked(x.doc(), x.score())).toList();
    }

    public Chunk chunk(int i) {
        return chunks.get(i);
    }

    public int vectors() {
        return vecChunk.length;
    }

    public int size() {
        return chunks.size();
    }

    public Stats stats() {
        return stats;
    }
}
