package io.github.overdozya.privacyask.verify;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.github.overdozya.privacyask.answer.Answer;
import io.github.overdozya.privacyask.answer.AnswerOutput;
import io.github.overdozya.privacyask.knowledge.Annexes;
import io.github.overdozya.privacyask.knowledge.KnowledgeSnapshot;
import io.github.overdozya.privacyask.knowledge.LawUnits;
import io.github.overdozya.privacyask.knowledge.Ledger;
import io.github.overdozya.privacyask.packages.PackageOpener;

/**
 * A7 인용 검증(03 4.2 A7 1~3, 06 1.5 패키지 16). 모두 코드이고 지식 스냅샷만 본다.
 * <ol>
 * <li>인용 키가 이번 꾸러미(일반은 G2 고른 조·붙인 원문 단위) 안, 원장 행 ID가 A3 후보(이 사안 원장 사실에 붙은 행) 안</li>
 * <li>인용한 판의 해시 = A4가 기록한 기준일 판 해시 = 스냅샷의 기준일 판 해시(C13 citationOk와 같은 계산), 원장 행은 사안 처리방침 판의 행 해시</li>
 * <li>기준일(A4 open_date)에 시행 중인 판이 있음(조문·별표. 안내서 절은 날짜가 없어 늘 시행 중으로 본다, S4 다른 곳 4)</li>
 * <li>쟁점마다 즉답 근거(문서 정책 ANSWER_BASIS이고 꾸러미 항목이 즉답 근거로 표시한 것) 인용이 하나 이상. 즉답 근거가 아닌 인용은
 * '해석 참고'로 남기고 즉답 근거로 세지 않는다</li>
 * </ol>
 * 문제가 하나라도 있으면 A5를 거절 이유를 붙여 1회 다시 부르고(LlmGateway 업무 검사), 또 실패하면 LLM_FAILED.
 */
public final class CitationVerifier {
    /** 문제 종류. */
    public static final String OUTSIDE_PACKAGE = "OUTSIDE_PACKAGE", ROW_OUTSIDE = "ROW_OUTSIDE",
            HASH_MISMATCH = "HASH_MISMATCH", NOT_IN_FORCE = "NOT_IN_FORCE", NO_ANSWER_BASIS = "NO_ANSWER_BASIS";
    /** 인용 쓰임. */
    public static final String USE_BASIS = "즉답 근거", USE_REFERENCE = "해석 참고", USE_LEDGER = "처리방침 행";

    /** 쟁점 하나(일반 답은 flow·issue가 null인 하나)의 인용. */
    public record IssueCites(Integer flow, String issue, List<String> cites, List<String> ledgerRows) {}

    /** 인용된 원문 단위(코드가 붙인 것). kind LAW_UNIT·ANNEX·GUIDE_SECTION. */
    public record Cited(String key, String kind, String textHash, boolean answerBasis, String usePolicy) {}

    public record Problem(String kind, Integer flow, String issue, String key, String detail) {
        String line() {
            return (flow == null ? "" : "흐름 " + flow + " ") + (issue == null ? "" : "'" + issue + "' ")
                    + (key == null ? "" : key + ": ") + kind + " " + detail;
        }
    }

    /** 확인한 인용 하나(판정 인용 행·검토 묶음에 씀). use = 즉답 근거·해석 참고·처리방침 행. */
    public record Checked(Integer flow, String issue, String key, String kind, String textHash, String ledgerVersion,
                          String use, boolean ok) {}

    public record Report(LocalDate date, List<Checked> cites, List<Problem> problems) {
        public boolean ok() {
            return problems.isEmpty();
        }

        /** 업무 검사 거절 이유 한 줄(앞 4개). */
        public String reason() {
            if (problems.isEmpty()) {
                return null;
            }
            List<String> ls = problems.stream().map(Problem::line).toList();
            return "A7 인용 검증: " + String.join("; ", ls.subList(0, Math.min(4, ls.size())));
        }

        public Map<String, Object> summary() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", String.valueOf(date));
            m.put("cites", cites.size());
            m.put("basis", cites.stream().filter(c -> USE_BASIS.equals(c.use())).count());
            m.put("reference", cites.stream().filter(c -> USE_REFERENCE.equals(c.use())).count());
            m.put("ledger_rows", cites.stream().filter(c -> USE_LEDGER.equals(c.use())).count());
            m.put("problems", problems);
            return m;
        }
    }

    /**
     * 기준 재료. allowed = 인용해도 되는 키(이번 꾸러미 키 또는 G2 키), sources = 키 → 코드가 붙인 원문 단위, recorded = A4가 기록한
     * 키 → 해시(없으면 스냅샷만 본다), rows = A3 후보 행, ledgerVersion = 사안 처리방침 판, date = 기준일 판을 연 날(A4 open_date),
     * needBasis = 쟁점마다 즉답 근거 인용을 요구하나(일반 답 문장은 고른 조가 모두 즉답 근거라 늘 참).
     */
    public record Basis(Set<String> allowed, Map<String, Cited> sources, Map<String, String> recorded, Set<String> rows,
                        String ledgerVersion, LocalDate date, boolean needBasis) {}

    private final KnowledgeSnapshot k;

    public CitationVerifier(KnowledgeSnapshot k) {
        this.k = k;
    }

    public Report verify(Basis b, List<IssueCites> issues) {
        List<Checked> out = new ArrayList<>();
        List<Problem> bad = new ArrayList<>();
        for (IssueCites i : issues) {
            boolean anyBasis = false;
            for (String key : i.cites()) {
                List<Cited> units = units(b, key);
                if (units.isEmpty()) {
                    bad.add(new Problem(OUTSIDE_PACKAGE, i.flow(), i.issue(), key, "넘긴 꾸러미 밖"));
                    continue;
                }
                for (Cited c : units) {
                    anyBasis |= check(b, i, c, out, bad);
                }
            }
            for (String row : i.ledgerRows()) {
                Optional<Ledger.Row> r = k.ledger().row(row);
                String why = null;
                if (!b.rows().contains(row) || r.isEmpty()) {
                    bad.add(new Problem(ROW_OUTSIDE, i.flow(), i.issue(), row, "A3 후보 행 밖"));
                    continue;
                }
                if (!r.get().versionId().equals(b.ledgerVersion())) {
                    why = "처리방침 판 " + r.get().versionId() + " ≠ 사안 " + b.ledgerVersion();
                    bad.add(new Problem(HASH_MISMATCH, i.flow(), i.issue(), row, why));
                }
                out.add(new Checked(i.flow(), i.issue(), row, "LEDGER_ROW", r.get().textHash(), r.get().versionId(),
                        USE_LEDGER, why == null));
            }
            if (b.needBasis() && !anyBasis && !i.cites().isEmpty()
                    && bad.stream().noneMatch(p -> eq(p.flow(), i.flow()) && eq(p.issue(), i.issue())
                    && !NO_ANSWER_BASIS.equals(p.kind()) && !ROW_OUTSIDE.equals(p.kind()))) {
                bad.add(new Problem(NO_ANSWER_BASIS, i.flow(), i.issue(), null,
                        "즉답 근거 문서 인용 없음(해석 참고만: " + String.join(", ", i.cites()) + ")"));
            }
        }
        return new Report(b.date(), List.copyOf(out), List.copyOf(bad));
    }

    /** 키가 가리키는 인용 대상: 그 키, 또는 조 단위 키('…|26|' 꼴, G2 고른 조)면 그 접두의 원문 단위 전부. 허용 키 밖이면 빈 목록. */
    static List<Cited> units(Basis b, String key) {
        if (!b.allowed().contains(key)) {
            return List.of();
        }
        Cited c = b.sources().get(key);
        if (c != null) {
            return List.of(c);
        }
        if (!key.endsWith("|")) {
            return List.of();
        }
        return b.sources().values().stream().filter(x -> x.key().startsWith(key)).toList();
    }

    /** 인용 대상 하나: 판·해시·정책. 즉답 근거로 셀 수 있으면 참. */
    private boolean check(Basis b, IssueCites i, Cited c, List<Checked> out, List<Problem> bad) {
        String why = versionProblem(c, b.date());
        if (why != null) {
            bad.add(new Problem(why.startsWith(NOT_IN_FORCE) ? NOT_IN_FORCE : HASH_MISMATCH, i.flow(), i.issue(),
                    c.key(), why.substring(why.indexOf(' ') + 1)));
        } else if (b.recorded() != null && b.recorded().containsKey(c.key())
                && !b.recorded().get(c.key()).equals(c.textHash())) {
            why = HASH_MISMATCH;
            bad.add(new Problem(HASH_MISMATCH, i.flow(), i.issue(), c.key(),
                    "A4 기록 해시 " + head(b.recorded().get(c.key())) + " ≠ 인용 해시 " + head(c.textHash())));
        }
        boolean basis = c.answerBasis() && "ANSWER_BASIS".equals(c.usePolicy());
        out.add(new Checked(i.flow(), i.issue(), c.key(), c.kind(), c.textHash(), null,
                basis ? USE_BASIS : USE_REFERENCE, why == null));
        return basis && why == null;
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    /** 기준일 판과 해시. 문제가 없으면 null, 있으면 "종류 글". */
    String versionProblem(Cited c, LocalDate date) {
        switch (c.kind()) {
            case PackageOpener.LAW_UNIT -> {
                LawUnits.At at = k.laws().at(c.key(), date);
                if (at.inForce() == null) {
                    return NOT_IN_FORCE + " " + date + "에 시행 중인 판 없음";
                }
                if (!at.inForce().textHash().equals(c.textHash())) {
                    return HASH_MISMATCH + " " + date + " 시행 판 해시 " + head(at.inForce().textHash()) + " ≠ 인용 "
                            + head(c.textHash());
                }
                return null;
            }
            case PackageOpener.ANNEX -> {
                Annexes.At at = k.annexes().at(c.key(), date);
                if (at.inForce() == null) {
                    return NOT_IN_FORCE + " " + date + "에 시행 중인 판 없음";
                }
                if (!at.inForce().textHash().equals(c.textHash())) {
                    return HASH_MISMATCH + " " + date + " 시행 판 해시 ≠ 인용";
                }
                return null;
            }
            case PackageOpener.GUIDE_SECTION -> {
                Optional<String> h = k.sections().hash(c.key());
                if (h.isEmpty()) {
                    return NOT_IN_FORCE + " 안내서 절 없음";
                }
                return h.get().equals(c.textHash()) ? null : HASH_MISMATCH + " 절 해시 ≠ 인용";
            }
            default -> {
                return HASH_MISMATCH + " 모르는 종류 " + c.kind();
            }
        }
    }

    static String head(String h) {
        return h == null ? "null" : h.substring(0, Math.min(10, h.length()));
    }

    // ───────── 재료 짓기 ─────────

    /** 꾸러미 원문 단위 → 인용 대상. */
    public static Map<String, Cited> sources(PackageOpener.Opened o) {
        Map<String, Cited> m = new LinkedHashMap<>();
        o.sources().forEach((key, s) -> m.put(key, new Cited(key, s.kind(), s.textHash(), s.answerBasis(), s.usePolicy())));
        return m;
    }

    /** 답에 붙은 원문 단위 → 인용 대상(저장된 A5 출력을 다시 볼 때). */
    public static Map<String, Cited> sources(Answer a) {
        Map<String, Cited> m = new LinkedHashMap<>();
        if (a.sources() != null) {
            for (Answer.Source s : a.sources()) {
                m.put(s.key(), new Cited(s.key(), s.kind(), s.textHash(), s.answerBasis(), s.usePolicy()));
            }
        }
        return m;
    }

    public static List<IssueCites> issues(AnswerOutput out) {
        return out.issues().stream().map(i -> new IssueCites(i.flow(), i.issue(), i.cites(), i.ledgerRows())).toList();
    }

    public static List<IssueCites> issues(Answer a) {
        if (Answer.GENERAL.equals(a.kind())) {
            List<String> cites = new ArrayList<>(new LinkedHashSet<>(a.answerCites() == null
                    ? a.picks().stream().map(Answer.Pick::key).toList() : a.answerCites()));
            return cites.isEmpty() ? List.of() : List.of(new IssueCites(null, null, cites, List.of()));
        }
        return a.issues().stream().map(i -> new IssueCites(i.flow(), i.issue(), i.cites(), i.ledgerRows())).toList();
    }
}
