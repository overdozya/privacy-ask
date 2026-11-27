package io.github.overdozya.privacyask.knowledge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 조문 연결(kb_links: 본문참조·위임·별표·준용). 꾸러미 펼치기·A6에서 쓸 자리. */
public final class KbLinks {

    public record Link(int linkId, String linkType, String srcKey, Integer srcChunkId, Integer srcAnnexId,
                       String dstKey, Integer dstDocId, Integer dstChunkId, Integer dstAnnexId, String resolution,
                       String unresolvedReason, String evidence, String origin) {}

    private final Map<Integer, List<Link>> fromChunk;
    private final Map<Integer, List<Link>> toChunk;
    private final int size;

    KbLinks(List<Link> links) {
        Map<Integer, List<Link>> f = new HashMap<>(), t = new HashMap<>();
        for (Link l : links) {
            if (l.srcChunkId() != null) {
                f.computeIfAbsent(l.srcChunkId(), k -> new ArrayList<>()).add(l);
            }
            if (l.dstChunkId() != null) {
                t.computeIfAbsent(l.dstChunkId(), k -> new ArrayList<>()).add(l);
            }
        }
        f.replaceAll((k, v) -> List.copyOf(v));
        t.replaceAll((k, v) -> List.copyOf(v));
        this.fromChunk = Map.copyOf(f);
        this.toChunk = Map.copyOf(t);
        this.size = links.size();
    }

    public List<Link> from(int chunkId) {
        return fromChunk.getOrDefault(chunkId, List.of());
    }

    public List<Link> to(int chunkId) {
        return toChunk.getOrDefault(chunkId, List.of());
    }

    public int size() {
        return size;
    }
}
