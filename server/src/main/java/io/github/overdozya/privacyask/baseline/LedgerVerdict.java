package io.github.overdozya.privacyask.baseline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.overdozya.privacyask.baseline.LedgerCandidates.Candidates;
import io.github.overdozya.privacyask.baseline.LedgerCandidates.Group;
import io.github.overdozya.privacyask.baseline.LedgerCites.Cite;
import io.github.overdozya.privacyask.baseline.LedgerCites.RowCite;
import io.github.overdozya.privacyask.knowledge.Catalog;
import io.github.overdozya.privacyask.knowledge.Ledger;

/**
 * 기준선 업무 검사와 판정(06 3.3 A3 기준선, 04 1.0).
 * 검사: (흐름, 원장 사실)마다 인용 하나, 일치 유형이 그 사실 보기 안, 행 ID가 그 묶음 후보 안, 인용이 그 행 글 안(공백 뺀 비교),
 * 명시·포괄은 행이 있고 없음은 행이 없음, 명시 행은 한 첨부에서만(L2 첨부4 명시 / 첨부5 명시를 가르려고).
 * 판정: 명시 → '명시' 보기 + MATCHED, 포괄 → 포괄 보기 + INFERRED, 없음 → 일치 없음 보기 + INFERRED(C09).
 * 후보가 없는 묶음은 LLM 없이: 서비스가 원장에 없으면 '서비스 못 찾음'(있는 사실만), 그 밖은 '없음'.
 */
public final class LedgerVerdict {
    public static final String EXPLICIT = "명시", BROAD = "포괄", NONE = "없음";
    static final String NOT_FOUND_SERVICE = "못 찾음";
    static final String SEP = " … ";
    private static final Pattern SPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    /** 사실 한 행(source LEDGER). rows·quote는 일치 없음이면 비어 있음. */
    public record Verdict(int flow, String factId, String label, String status, List<String> rows, String quote,
                          String match, String note) {}

    private LedgerVerdict() {}

    /** 업무 검사. 통과면 null. */
    public static String check(Candidates c, LedgerCites out) {
        List<String> bad = new ArrayList<>();
        Map<String, Group> want = new HashMap<>();
        for (Group g : c.groups()) {
            if (!g.rows().isEmpty()) {
                g.flows().forEach(n -> want.put(key(n, g.factId()), g));
            }
        }
        Set<String> seen = new HashSet<>();
        for (Cite ct : out.cites()) {
            String k = key(ct.flow(), ct.fact());
            Group g = want.get(k);
            if (g == null) {
                bad.add("묻지 않은 (흐름 " + ct.flow() + ", " + ct.fact() + ")");
                continue;
            }
            if (!seen.add(k)) {
                bad.add("(흐름 " + ct.flow() + ", " + ct.fact() + ") 두 번");
                continue;
            }
            if (!g.allowedMatch().contains(ct.match())) {
                bad.add(ct.fact() + " 일치 유형 '" + ct.match() + "'는 " + g.allowedMatch() + " 밖");
            }
            if (NONE.equals(ct.match())) {
                if (!ct.rows().isEmpty()) {
                    bad.add(ct.fact() + " 흐름 " + ct.flow() + ": 없음인데 행 " + ct.rows().size() + "개");
                }
                continue;
            }
            if (ct.rows().isEmpty()) {
                bad.add(ct.fact() + " 흐름 " + ct.flow() + ": " + ct.match() + "인데 행 없음");
            }
            Set<String> atts = new HashSet<>();
            for (RowCite rc : ct.rows()) {
                Optional<Ledger.Row> row = g.rows().stream().filter(r -> r.rowId().equals(rc.rowId())).findFirst();
                if (row.isEmpty()) {
                    bad.add(ct.fact() + " 행 " + rc.rowId() + "는 후보 밖");
                    continue;
                }
                atts.add(row.get().attachment());
                if (rc.quote() == null || rc.quote().isBlank() || !inRow(row.get(), rc.quote())) {
                    bad.add(rc.rowId() + " 인용 '" + rc.quote() + "'가 그 행 글에 없음");
                }
            }
            if (EXPLICIT.equals(ct.match()) && atts.size() > 1) {
                bad.add(ct.fact() + " 흐름 " + ct.flow() + ": 명시 행이 여러 첨부 " + atts);
            }
        }
        for (String k : want.keySet()) {
            if (!seen.contains(k)) {
                bad.add("(" + k.replace('\u0000', ',') + ") 결과 없음");
            }
        }
        return bad.isEmpty() ? null : String.join("; ", bad.subList(0, Math.min(3, bad.size())));
    }

    /** 인용 조각(' … '로 이은 것 포함)이 모두 행 글 안에 있나(공백 뺀 비교 = CaseStore C03 원장 규칙). */
    static boolean inRow(Ledger.Row row, String quote) {
        String hay = SPACE.matcher(row.text()).replaceAll("");
        for (String piece : quote.split(Pattern.quote(SEP), -1)) {
            String p = SPACE.matcher(piece).replaceAll("");
            if (p.isEmpty() || !hay.contains(p)) {
                return false;
            }
        }
        return true;
    }

    /** 판정. out은 검사를 지난 것(후보가 하나도 없으면 null이어도 됨). 흐름·원장 사실 순. */
    public static List<Verdict> decide(Candidates c, LedgerCites out, Catalog catalog) {
        Map<String, Cite> cites = new HashMap<>();
        if (out != null) {
            out.cites().forEach(ct -> cites.put(key(ct.flow(), ct.fact()), ct));
        }
        List<Verdict> list = new ArrayList<>();
        for (Group g : c.groups()) {
            Catalog.Fact f = catalog.fact(g.factId()).orElseThrow();
            for (int n : g.flows()) {
                if (g.rows().isEmpty()) {
                    boolean noService = g.kind() == Ledger.MatchKind.NONE;
                    String label = noMatchLabel(f, noService);
                    list.add(new Verdict(n, g.factId(), label, "INFERRED", List.of(), null, NONE,
                            "후보 행 없음(" + g.attachments() + ", 서비스 '" + g.service() + "' "
                                    + (noService ? "원장에 없음" : "판 " + c.ledgerVersion() + "에 행 없음") + ")"));
                    continue;
                }
                Cite ct = cites.get(key(n, g.factId()));
                if (ct == null) {
                    throw new IllegalStateException("검사를 지난 출력에 (" + n + ", " + g.factId() + ")가 없음");
                }
                if (NONE.equals(ct.match())) {
                    list.add(new Verdict(n, g.factId(), noMatchLabel(f, false), "INFERRED", List.of(), null, NONE,
                            "후보 " + g.rows().size() + "행에 일치 없음" + (ct.reason() == null ? "" : ": " + ct.reason())));
                    continue;
                }
                List<String> rows = ct.rows().stream().map(RowCite::rowId).distinct().toList();
                String quote = String.join(SEP, ct.rows().stream().map(RowCite::quote).toList());
                if (EXPLICIT.equals(ct.match())) {
                    String att = g.rows().stream().filter(r -> r.rowId().equals(rows.get(0))).findFirst()
                            .orElseThrow().attachment();
                    list.add(new Verdict(n, g.factId(), explicitLabel(f, att), "MATCHED", rows, quote, EXPLICIT, null));
                } else {
                    list.add(new Verdict(n, g.factId(), broadLabel(f), "INFERRED", rows, quote, BROAD, ct.reason()));
                }
            }
        }
        list.sort(java.util.Comparator.comparingInt(Verdict::flow)
                .thenComparingInt(v -> catalog.fact(v.factId()).map(Catalog.Fact::ord).orElse(0)));
        return list;
    }

    /** '명시'가 든 보기. 여럿이면(L2) 행의 첨부로 시작하는 것. */
    static String explicitLabel(Catalog.Fact f, String attachment) {
        List<Catalog.Option> ex = f.options().stream()
                .filter(o -> "MATCH".equals(o.kind()) && o.label().contains(EXPLICIT)).toList();
        if (ex.size() == 1) {
            return ex.get(0).label();
        }
        return ex.stream().filter(o -> o.label().startsWith(attachment + " ")).findFirst()
                .orElseThrow(() -> new IllegalStateException(f.factId() + "에 " + attachment + " 명시 보기가 없음"))
                .label();
    }

    static String broadLabel(Catalog.Fact f) {
        return f.options().stream().filter(o -> "MATCH".equals(o.kind()) && !o.label().contains(EXPLICIT))
                .findFirst().orElseThrow().label();
    }

    static String noMatchLabel(Catalog.Fact f, boolean serviceNotFound) {
        List<Catalog.Option> no = f.options().stream().filter(o -> "NO_MATCH".equals(o.kind())).toList();
        if (serviceNotFound) {
            Optional<Catalog.Option> nf = no.stream().filter(o -> o.label().contains(NOT_FOUND_SERVICE)).findFirst();
            if (nf.isPresent()) {
                return nf.get().label();
            }
        }
        return no.stream().filter(o -> !o.label().contains(NOT_FOUND_SERVICE)).findFirst()
                .orElse(no.get(0)).label();
    }

    private static String key(int flow, String fact) {
        return flow + "\u0000" + fact;
    }
}
