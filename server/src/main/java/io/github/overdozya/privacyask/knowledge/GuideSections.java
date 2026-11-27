package io.github.overdozya.privacyask.knowledge;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * 안내서 절(guide_sections) + 절에 속한 청크. 절 해시(인용 C13, store.py section_hash):
 * 그 절과 아래 절에 속한 청크의 text_hash를 chunk_id 순으로 줄바꿈으로 이어 sha256. 적재 때 한 번 계산해 둔다.
 */
public final class GuideSections {

    public record Section(String sectionId, int docId, String parentId, int depth, String title, String pathText,
                          int ord, String num, boolean leaf, Integer pageFrom, Integer pageTo, int chunkCount,
                          int charCount) {}

    private final Map<String, Section> byId;
    private final Map<String, List<String>> children;
    private final Map<String, List<Integer>> ownChunks;
    private final Map<String, List<Integer>> subtreeChunks;
    private final Map<String, String> hash;

    GuideSections(List<Section> sections, LawUnits units) {
        Map<String, Section> m = new HashMap<>();
        Map<String, List<String>> ch = new HashMap<>();
        for (Section s : sections) {
            m.put(s.sectionId(), s);
        }
        List<Section> sorted = new ArrayList<>(sections);
        sorted.sort(java.util.Comparator.comparingInt(Section::docId).thenComparingInt(Section::ord));
        for (Section s : sorted) {
            if (s.parentId() != null) {
                ch.computeIfAbsent(s.parentId(), k -> new ArrayList<>()).add(s.sectionId());
            }
        }
        Map<String, TreeSet<Integer>> own = new HashMap<>();
        Map<String, TreeSet<Integer>> sub = new HashMap<>();
        for (LawUnits.Unit u : units.all()) {
            if (u.sectionId() == null) {
                continue;
            }
            own.computeIfAbsent(u.sectionId(), k -> new TreeSet<>()).add(u.chunkId());
            for (String id = u.sectionId(); id != null; id = m.containsKey(id) ? m.get(id).parentId() : null) {
                sub.computeIfAbsent(id, k -> new TreeSet<>()).add(u.chunkId());
            }
        }
        Map<String, String> h = new HashMap<>();
        for (var e : sub.entrySet()) {
            StringBuilder sb = new StringBuilder();
            for (int cid : e.getValue()) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(units.byChunkId(cid).orElseThrow().textHash());
            }
            h.put(e.getKey(), Hashes.sha256(sb.toString().getBytes(StandardCharsets.UTF_8)));
        }
        this.byId = Map.copyOf(m);
        ch.replaceAll((k, v) -> List.copyOf(v));
        this.children = Map.copyOf(ch);
        Map<String, List<Integer>> o = new HashMap<>();
        own.forEach((k, v) -> o.put(k, List.copyOf(v)));
        this.ownChunks = Map.copyOf(o);
        Map<String, List<Integer>> s2 = new HashMap<>();
        sub.forEach((k, v) -> s2.put(k, List.copyOf(v)));
        this.subtreeChunks = Map.copyOf(s2);
        this.hash = Map.copyOf(h);
    }

    public Optional<Section> get(String sectionId) {
        return Optional.ofNullable(byId.get(sectionId));
    }

    public boolean has(String sectionId) {
        return byId.containsKey(sectionId);
    }

    public List<String> children(String sectionId) {
        return children.getOrDefault(sectionId, List.of());
    }

    /** 그 절에 바로 속한 청크 ID(chunk_id 순). */
    public List<Integer> ownChunkIds(String sectionId) {
        return ownChunks.getOrDefault(sectionId, List.of());
    }

    /** 그 절과 아래 절의 청크 ID(chunk_id 순). */
    public List<Integer> chunkIds(String sectionId) {
        return subtreeChunks.getOrDefault(sectionId, List.of());
    }

    /** 절 해시. 청크가 없는 절이면 빈 값. */
    public Optional<String> hash(String sectionId) {
        return Optional.ofNullable(hash.get(sectionId));
    }

    public int size() {
        return byId.size();
    }
}
