package io.github.overdozya.privacyask.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 안내서 청크 글자 검색(메모리): 글자 2-그램 + BM25. 06 6.2 "지식은 실행 중 DB에서 찾지 않고 스냅샷에서 찾는다"를 따라 MySQL FULLTEXT 대신
 * 기동 때 스냅샷 글로 만든다(MySQL ngram 파서와 같은 2글자 단위). 띄어쓰기로 나눈 덩어리 안에서만 2-그램을 짓고, 한 글자 덩어리는 그 글자.
 */
public final class TextIndex {
    static final double K1 = 1.2, B = 0.75;

    private final Map<String, int[]> postings;   // 2-그램 → [문서 번호, 횟수, 문서 번호, 횟수, …]
    private final int[] docLen;
    private final double avgLen;

    public record Scored(int doc, double score) {}

    public TextIndex(List<String> texts) {
        Map<String, List<int[]>> tmp = new HashMap<>();
        docLen = new int[texts.size()];
        long total = 0;
        for (int d = 0; d < texts.size(); d++) {
            Map<String, Integer> tf = new HashMap<>();
            List<String> grams = grams(texts.get(d));
            for (String g : grams) {
                tf.merge(g, 1, Integer::sum);
            }
            docLen[d] = grams.size();
            total += grams.size();
            for (var e : tf.entrySet()) {
                tmp.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(new int[] {d, e.getValue()});
            }
        }
        Map<String, int[]> p = new HashMap<>(tmp.size() * 2);
        tmp.forEach((g, l) -> {
            int[] a = new int[l.size() * 2];
            for (int i = 0; i < l.size(); i++) {
                a[2 * i] = l.get(i)[0];
                a[2 * i + 1] = l.get(i)[1];
            }
            p.put(g, a);
        });
        postings = p;
        avgLen = texts.isEmpty() ? 1 : Math.max(1.0, (double) total / texts.size());
    }

    /** 소문자, 글자·숫자만 남기고 나머지는 띄어쓰기. 덩어리마다 2-그램(덩어리가 한 글자면 그 글자). */
    static List<String> grams(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        StringBuilder run = new StringBuilder();
        s.toLowerCase().codePoints().forEach(c -> {
            if (Character.isLetterOrDigit(c)) {
                run.appendCodePoint(c);
            } else {
                flush(run, out);
            }
        });
        flush(run, out);
        return out;
    }

    private static void flush(StringBuilder run, List<String> out) {
        int[] cps = run.codePoints().toArray();
        if (cps.length == 1) {
            out.add(new String(cps, 0, 1));
        }
        for (int i = 0; i + 1 < cps.length; i++) {
            out.add(new String(cps, i, 2));
        }
        run.setLength(0);
    }

    /** 점수 높은 순 상위 n(같으면 문서 번호 순). 질의 2-그램은 한 번씩만 센다. */
    public List<Scored> search(String query, int n) {
        Set<String> q = new LinkedHashSet<>(grams(query));
        Map<Integer, Double> score = new HashMap<>();
        int nDocs = docLen.length;
        for (String g : q) {
            int[] p = postings.get(g);
            if (p == null) {
                continue;
            }
            int df = p.length / 2;
            double idf = Math.log(1 + (nDocs - df + 0.5) / (df + 0.5));
            for (int i = 0; i < p.length; i += 2) {
                int d = p[i], tf = p[i + 1];
                double norm = tf * (K1 + 1) / (tf + K1 * (1 - B + B * docLen[d] / avgLen));
                score.merge(d, idf * norm, Double::sum);
            }
        }
        return score.entrySet().stream()
                .sorted((a, b) -> a.getValue().equals(b.getValue()) ? Integer.compare(a.getKey(), b.getKey())
                        : Double.compare(b.getValue(), a.getValue()))
                .limit(n).map(e -> new Scored(e.getKey(), e.getValue())).toList();
    }

    public int size() {
        return docLen.length;
    }

    public int terms() {
        return postings.size();
    }
}
