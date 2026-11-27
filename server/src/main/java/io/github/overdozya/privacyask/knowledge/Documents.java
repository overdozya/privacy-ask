package io.github.overdozya.privacyask.knowledge;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 법령·안내서 문서(kb_documents). 사용 정책(use_policy·use_scope)은 문서별로 들고 있다(06 3.1). */
public final class Documents {

    public record Doc(int docId, String sourceType, String title, String lawName, String lawKind, int stage,
                      Integer parentDocId, LocalDate effectiveDate, String usePolicy, List<String> useScope,
                      String guideVersion) {
        public boolean isLaw() {
            return "LAW".equals(sourceType);
        }
    }

    private final Map<Integer, Doc> byId;
    private final Map<String, List<Doc>> byLawName;

    Documents(List<Doc> docs) {
        Map<Integer, Doc> m = new LinkedHashMap<>();
        Map<String, List<Doc>> n = new LinkedHashMap<>();
        for (Doc d : docs) {
            m.put(d.docId(), d);
            n.computeIfAbsent(d.lawName(), k -> new ArrayList<>()).add(d);
        }
        n.replaceAll((k, v) -> List.copyOf(v));
        this.byId = Map.copyOf(m);
        this.byLawName = Map.copyOf(n);
    }

    public Optional<Doc> get(int docId) {
        return Optional.ofNullable(byId.get(docId));
    }

    /** kb_documents.law_name 글자 그대로(A3 적용 법령 맞추기, 06 3.3). */
    public List<Doc> byLawName(String lawName) {
        return byLawName.getOrDefault(lawName, List.of());
    }

    public Collection<Doc> all() {
        return byId.values();
    }

    public int size() {
        return byId.size();
    }
}
