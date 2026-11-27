package io.github.overdozya.privacyask.knowledge;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 별표·서식(kb_annexes). effective_to 칸이 없으므로 기준일 판 = CURRENT·PENDING 중 effective_from <= D(NULL은 처음부터)
 * 가운데 가장 늦은 것.
 */
public final class Annexes {

    public record Annex(int annexId, int docId, String annexKey, String kind, int annexNo, int annexSub, String label,
                        String title, String text, String textRows, String status, LocalDate effectiveFrom,
                        String textHash) {
        public boolean isLive() {
            return LawUnits.CURRENT.equals(status) || LawUnits.PENDING.equals(status);
        }
    }

    public record At(String annexKey, LocalDate date, Annex inForce, List<Annex> upcoming) {}

    private static final Comparator<Annex> BY_FROM = Comparator.comparing(Annex::effectiveFrom,
            Comparator.nullsFirst(Comparator.naturalOrder())).thenComparingInt(Annex::annexId);

    private final Map<String, List<Annex>> byKey;
    private final Map<Integer, Annex> byId;

    Annexes(List<Annex> all) {
        Map<String, List<Annex>> m = new HashMap<>();
        Map<Integer, Annex> i = new HashMap<>();
        for (Annex a : all) {
            m.computeIfAbsent(a.annexKey(), k -> new ArrayList<>()).add(a);
            i.put(a.annexId(), a);
        }
        m.replaceAll((k, v) -> v.stream().sorted(BY_FROM).toList());
        this.byKey = Map.copyOf(m);
        this.byId = Map.copyOf(i);
    }

    public List<Annex> versions(String annexKey) {
        return byKey.getOrDefault(annexKey, List.of());
    }

    public boolean has(String annexKey) {
        return byKey.containsKey(annexKey);
    }

    public Optional<Annex> byId(int annexId) {
        return Optional.ofNullable(byId.get(annexId));
    }

    public At at(String annexKey, LocalDate date) {
        Annex best = null;
        List<Annex> later = new ArrayList<>();
        for (Annex a : versions(annexKey)) {
            if (!a.isLive()) {
                continue;
            }
            if (a.effectiveFrom() == null || !a.effectiveFrom().isAfter(date)) {
                if (best == null || BY_FROM.compare(a, best) > 0) {
                    best = a;
                }
            } else {
                later.add(a);
            }
        }
        return new At(annexKey, date, best, List.copyOf(later));
    }

    /** C13: 그 키의 어느 판이든 text_hash가 같음. */
    public boolean hashMatches(String annexKey, String textHash) {
        return versions(annexKey).stream().anyMatch(a -> textHash.equals(a.textHash()));
    }

    public int size() {
        return byId.size();
    }
}
