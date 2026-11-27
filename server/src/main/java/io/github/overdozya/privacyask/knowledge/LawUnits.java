package io.github.overdozya.privacyask.knowledge;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 조문·안내서 청크(kb_chunks)를 unit_key별 판 목록으로 든다.
 * 기준일 D에 시행 중인 판(build/db/build_db.py IN_FORCE_SQL과 같은 규칙): status CURRENT·PENDING,
 * effective_from <= D < effective_to(NULL은 끝 없음) 중 effective_from이 가장 늦은 한 행.
 * effective_from이 NULL인 행(안내서 청크)은 날짜와 무관하게 시행 중으로 본다.
 */
public final class LawUnits {
    public static final String CURRENT = "CURRENT", PENDING = "PENDING", ARCHIVED = "ARCHIVED";

    public record Unit(int chunkId, int docId, String unitKey, String article, String title, String status,
                       LocalDate effectiveFrom, LocalDate effectiveTo, Integer replacedBy, String text, String textHash,
                       String sectionId, Integer pageFrom, Integer pageTo, String chunkKey) {
        public boolean isLive() {
            return CURRENT.equals(status) || PENDING.equals(status);
        }

        public boolean inForceOn(LocalDate d) {
            return isLive() && (effectiveFrom == null || !effectiveFrom.isAfter(d))
                    && (effectiveTo == null || effectiveTo.isAfter(d));
        }
    }

    /** 기준일의 판 + 그 뒤 시행 예정 판(effective_from > D, 이른 순). inForce가 없으면 null. */
    public record At(String unitKey, LocalDate date, Unit inForce, List<Unit> upcoming) {
        public Optional<Unit> current() {
            return Optional.ofNullable(inForce);
        }
    }

    private static final Comparator<Unit> BY_FROM = Comparator.comparing(Unit::effectiveFrom,
            Comparator.nullsFirst(Comparator.naturalOrder())).thenComparingInt(Unit::chunkId);

    private final NavigableMap<String, List<Unit>> byKey;
    private final Map<Integer, Unit> byChunk;

    LawUnits(List<Unit> units) {
        TreeMap<String, List<Unit>> m = new TreeMap<>();
        Map<Integer, Unit> c = new HashMap<>();
        for (Unit u : units) {
            m.computeIfAbsent(u.unitKey(), k -> new ArrayList<>()).add(u);
            c.put(u.chunkId(), u);
        }
        m.replaceAll((k, v) -> v.stream().sorted(BY_FROM).toList());
        this.byKey = java.util.Collections.unmodifiableNavigableMap(m);
        this.byChunk = Map.copyOf(c);
    }

    /** 그 키의 모든 판(ARCHIVED 포함), effective_from 순. */
    public List<Unit> versions(String unitKey) {
        return byKey.getOrDefault(unitKey, List.of());
    }

    public boolean has(String unitKey) {
        return byKey.containsKey(unitKey);
    }

    /** 정확한 키이거나, '|'로 끝나는 조 단위 키(꾸러미 대상·해석 근거)면 그 접두를 가진 키가 하나라도 있음. */
    public boolean hasKeyOrArticle(String key) {
        if (byKey.containsKey(key)) {
            return true;
        }
        return key.endsWith("|") && !keysWithPrefix(key).isEmpty();
    }

    public Optional<Unit> byChunkId(int chunkId) {
        return Optional.ofNullable(byChunk.get(chunkId));
    }

    public Optional<Unit> inForce(String unitKey, LocalDate date) {
        return Optional.ofNullable(pick(versions(unitKey), date));
    }

    /** 키 + 기준일 → 판(시행 예정 포함). */
    public At at(String unitKey, LocalDate date) {
        List<Unit> vs = versions(unitKey);
        List<Unit> later = vs.stream()
                .filter(u -> u.isLive() && u.effectiveFrom() != null && u.effectiveFrom().isAfter(date)).toList();
        return new At(unitKey, date, pick(vs, date), later);
    }

    /** 접두(예: "개인정보보호법|15|")로 시작하는 키마다 기준일의 판(없는 키는 뺌), 키 순. */
    public List<Unit> inForceByPrefix(String prefix, LocalDate date) {
        List<Unit> out = new ArrayList<>();
        for (String k : keysWithPrefix(prefix)) {
            Unit u = pick(byKey.get(k), date);
            if (u != null) {
                out.add(u);
            }
        }
        return out;
    }

    public List<String> keysWithPrefix(String prefix) {
        return List.copyOf(byKey.subMap(prefix, true, prefix + Character.MAX_VALUE, false).keySet());
    }

    /** 인용 확인(C13): 그 키의 어느 판이든 text_hash가 같은 것이 있음. */
    public boolean hashMatches(String unitKey, String textHash) {
        return versions(unitKey).stream().anyMatch(u -> u.textHash().equals(textHash));
    }

    public int keyCount() {
        return byKey.size();
    }

    public int size() {
        return byChunk.size();
    }

    public java.util.Collection<Unit> all() {
        return byChunk.values();
    }

    private static Unit pick(List<Unit> vs, LocalDate d) {
        Unit best = null;
        for (Unit u : vs) {
            if (u.inForceOn(d) && (best == null || BY_FROM.compare(u, best) > 0)) {
                best = u;
            }
        }
        return best;
    }
}
