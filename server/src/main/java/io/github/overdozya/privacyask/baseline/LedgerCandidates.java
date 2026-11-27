package io.github.overdozya.privacyask.baseline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.overdozya.privacyask.knowledge.Catalog;
import io.github.overdozya.privacyask.knowledge.KnowledgeSnapshot;
import io.github.overdozya.privacyask.knowledge.Ledger;
import io.github.overdozya.privacyask.record.CaseState;

/**
 * 원장 후보 행 열거(06 3.3 A3 기준선, 04 1.0 "원장 조회"). 코드가 서비스명으로 후보를 모두 열거하고 모델은 고르기만 한다.
 * 원장 사실 = 보기가 원장 일치 종류(MATCH·NO_MATCH)뿐이고 볼 첨부가 있는 사실(L1 첨부1·2, L2 첨부4·5, L3 첨부7, L4 첨부10).
 * 흐름의 서비스명 = 흐름 service_name → 사안 service_name → 지금 F-S1 자유 입력. 없으면 그 흐름은 건너뛴다(A2가 F-S1을 먼저 묻는다).
 * 묶음 = (원장 사실, 서비스명): 같은 서비스의 흐름은 한 묶음에 모아 후보 행을 한 번만 보낸다. 행은 사안의 처리방침 판만.
 */
public final class LedgerCandidates {
    public static final String SERVICE_FACT = "F-S1";

    /** allowedMatch: 그 사실 보기로 낼 수 있는 일치 유형(명시·포괄·없음). */
    public record Group(String factId, String service, String serviceKey, Ledger.MatchKind kind, List<Integer> flows,
                        List<String> attachments, List<String> allowedMatch, String looksAt, List<Ledger.Row> rows) {
        public List<String> rowIds() {
            return rows.stream().map(Ledger.Row::rowId).toList();
        }
    }

    public record Candidates(String ledgerVersion, List<Group> groups, List<Integer> noService) {
        public boolean anyRows() {
            return groups.stream().anyMatch(g -> !g.rows().isEmpty());
        }

        public Optional<Group> group(String factId, int flow) {
            return groups.stream().filter(g -> g.factId().equals(factId) && g.flows().contains(flow)).findFirst();
        }
    }

    private LedgerCandidates() {}

    public static List<Catalog.Fact> ledgerFacts(Catalog catalog) {
        return catalog.facts().stream()
                .filter(f -> !f.ledgerAttachments().isEmpty() && !f.options().isEmpty()
                        && f.options().stream().allMatch(o -> "MATCH".equals(o.kind()) || "NO_MATCH".equals(o.kind())))
                .toList();
    }

    public static String ledgerVersion(CaseState st, KnowledgeSnapshot k) {
        String v = st.caseRow().ledgerVersion();
        return v != null ? v : k.ledger().latestVersion().orElse(null);
    }

    public static Optional<String> serviceFor(CaseState st, CaseState.Flow f) {
        if (f.serviceName() != null && !f.serviceName().isBlank()) {
            return Optional.of(f.serviceName());
        }
        if (st.caseRow().serviceName() != null && !st.caseRow().serviceName().isBlank()) {
            return Optional.of(st.caseRow().serviceName());
        }
        return st.current(null, SERVICE_FACT).flatMap(x -> x.values().stream().findFirst())
                .map(v -> v.detail()).filter(d -> d != null && !d.isBlank());
    }

    public static Candidates enumerate(CaseState st, List<Integer> flowNos, KnowledgeSnapshot k) {
        String version = ledgerVersion(st, k);
        List<Integer> noService = new ArrayList<>();
        Map<String, List<Integer>> byService = new LinkedHashMap<>();
        for (Integer n : flowNos) {
            CaseState.Flow f = st.flow(n).orElseThrow(() -> new IllegalArgumentException("없는 흐름 " + n));
            Optional<String> svc = serviceFor(st, f);
            if (svc.isEmpty()) {
                noService.add(n);
            } else {
                byService.computeIfAbsent(svc.get().strip(), s -> new ArrayList<>()).add(n);
            }
        }
        List<Group> groups = new ArrayList<>();
        for (Catalog.Fact lf : ledgerFacts(k.catalog())) {
            for (Map.Entry<String, List<Integer>> e : byService.entrySet()) {
                Ledger.ServiceMatch m = k.ledger().forService(e.getKey(), lf.ledgerAttachments());
                List<Ledger.Row> rows = m.rows().stream().filter(r -> r.versionId().equals(version)).toList();
                groups.add(new Group(lf.factId(), e.getKey(), m.key(), m.kind(), List.copyOf(e.getValue()),
                        lf.ledgerAttachments(), allowedMatch(lf), lf.question(), rows));
            }
        }
        return new Candidates(version, groups, noService);
    }

    /** 보기 라벨로 낼 수 있는 일치 유형: '명시'가 든 MATCH → 명시, 그 밖 MATCH → 포괄, NO_MATCH → 없음. */
    static List<String> allowedMatch(Catalog.Fact f) {
        List<String> out = new ArrayList<>();
        if (f.options().stream().anyMatch(o -> "MATCH".equals(o.kind()) && o.label().contains(LedgerVerdict.EXPLICIT))) {
            out.add(LedgerVerdict.EXPLICIT);
        }
        if (f.options().stream().anyMatch(o -> "MATCH".equals(o.kind()) && !o.label().contains(LedgerVerdict.EXPLICIT))) {
            out.add(LedgerVerdict.BROAD);
        }
        if (f.options().stream().anyMatch(o -> "NO_MATCH".equals(o.kind()))) {
            out.add(LedgerVerdict.NONE);
        }
        return out;
    }
}
