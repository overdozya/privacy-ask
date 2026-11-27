package io.github.overdozya.privacyask.answer;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.overdozya.privacyask.common.AppClock;
import io.github.overdozya.privacyask.common.CodeVersion;
import io.github.overdozya.privacyask.knowledge.Catalog;
import io.github.overdozya.privacyask.knowledge.Knowledge;
import io.github.overdozya.privacyask.knowledge.KnowledgeSnapshot;
import io.github.overdozya.privacyask.knowledge.Ledger;
import io.github.overdozya.privacyask.knowledge.Packages;
import io.github.overdozya.privacyask.llm.CallContext;
import io.github.overdozya.privacyask.llm.LlmGateway;
import io.github.overdozya.privacyask.llm.LlmResult;
import io.github.overdozya.privacyask.packages.PackageOpener;
import io.github.overdozya.privacyask.record.CaseInput;
import io.github.overdozya.privacyask.record.CaseInput.NewStep;
import io.github.overdozya.privacyask.record.CaseLock;
import io.github.overdozya.privacyask.record.CaseState;
import io.github.overdozya.privacyask.record.CaseStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A5 답변, 구체 사안(03 4.2 A5, 06 1.5 패키지 15). 입력: 사실(값·상태·근거 문구), 코드가 정한 흐름별 쟁점·등급, 원장 대조 결과(행 ID·행 글),
 * A4가 연 꾸러미 원문(키·역할). 출력: 쟁점별 의견·조치·인용(원문 키와 원장 행 ID로만)·conclusive·gap.
 *
 * <p>업무 검사(어기면 1회 다시, 또 어기면 LLM_FAILED): 넘긴 (흐름, 쟁점)마다 정확히 하나, 인용 키가 이번에 넘긴 꾸러미 원문 키 안, 원장 행 ID가
 * 넘긴 후보 행 안, gap의 흐름·쟁점·사실 ID가 있는 것. 인용 원문 대조·현행 검사·최종 출구는 A7(S9).
 *
 * <p>코드가 정하는 것: 해석 쟁점이 켜진 쟁점은 모델이 conclusive=true여도 미확정(D6). 그 쟁점이나 사안 전체에 gap이 있어도 미확정.
 * 더하는 이관 사유: 과거 기준일, gap 해석 필요·꾸러미 밖. 사실 부족 gap을 질문으로 돌릴지는 흐름(pa/flow)이 A2 후보를 보고 정한다.
 */
public class SpecificAnswerStep {
    public static final String STEP = "A5", PROMPT = "A5_answer", NAME = "답변(쟁점별 의견)";
    /** A5 출력 tool_requests의 도구 이름(S10, pa/tools GuideTools와 같은 글자). */
    public static final String TOOL_SEARCH = "search_guides", TOOL_READ = "read_section";
    static final int EXCERPT = 200;

    /** 쟁점 하나(코드가 정함). interp = 켜진 해석 쟁점(하위 꾸러미 것은 'PKG14.I29' 꼴). */
    public record IssueIn(int flow, String issue, String packageId, String grade, List<String> ruleIds,
                          List<String> conditionIds, List<String> interp) {}

    public record SlotIn(String status, List<String> values) {}

    public record FlowIn(int flow, Map<String, SlotIn> slots, List<IssueIn> issues) {}

    /** A2가 지금 물을 수 있는 후보(흐름 null = 공통 사실). 사실 부족 gap이 이 안의 사실을 말하면 질문으로 돌아간다. */
    public record Askable(Integer flow, String fact) {}

    /** asked: 사실 부족 gap으로 돌아가 물을 후보(없으면 null). */
    public record Result(LlmResult.Status status, Answer answer, List<Integer> steps, List<Long> callIds, String error,
                         int inputChars, Askable asked) {
        public boolean failed() {
            return status == LlmResult.Status.LLM_FAILED;
        }
    }

    private final LlmGateway llm;
    private final CaseStore store;
    private final CaseLock lock;
    private final JdbcClient jdbc;
    private final Knowledge knowledge;
    private final AppClock clock;
    private final CodeVersion code;
    private final JsonMapper json;

    public SpecificAnswerStep(LlmGateway llm, CaseStore store, CaseLock lock, JdbcClient jdbc, Knowledge knowledge,
                              AppClock clock, CodeVersion code, JsonMapper json) {
        this.llm = llm;
        this.store = store;
        this.lock = lock;
        this.jdbc = jdbc;
        this.knowledge = knowledge;
        this.clock = clock;
        this.code = code;
        this.json = json;
    }

    public Result run(CaseState st, List<FlowIn> flows, PackageOpener.Opened opened, PackageOpener.RefDate ref,
                      List<Askable> askable, Instant deadline) {
        return run(st, flows, opened, ref, askable, deadline, null);
    }

    /** extra: 이 단계 업무 검사 뒤에 더 보는 검사(S9 A7 인용 검증, 메인 허락). 거절이면 이유를 붙여 1회 다시, 또 거절이면 LLM_FAILED. */
    public Result run(CaseState st, List<FlowIn> flows, PackageOpener.Opened opened, PackageOpener.RefDate ref,
                      List<Askable> askable, Instant deadline, LlmGateway.Check<AnswerOutput> extra) {
        Call c = call(st, flows, opened, ref, ToolView.NONE, deadline, extra);
        return save(st, c, flows, opened, ref, askable, c.r().callIds(), List.of());
    }

    // ───────── A6 도구와 잇기(S10) ─────────

    /**
     * 이번 A5 호출에 보이는 A6 도구(S10). enabled = 도구를 줌(입력 tools·출력 tool_requests), remaining = 이 사안에 남은 도구 호출 수,
     * reduce = 입력 줄이기('해석' 역할 안내서 절은 글 없이 키·경로·제목만), results = 지금까지 도구 결과(입력 tools.results 그대로),
     * found = 도구가 글을 붙인 절(키 = 절 ID → 원문 단위. 인용해도 되고, 꾸러미 밖이면 출처 A6·'해석 참고').
     */
    public record ToolView(boolean enabled, int remaining, boolean reduce, List<Map<String, Object>> results,
                           Map<String, PackageOpener.Source> found) {
        public static final ToolView NONE = new ToolView(false, 0, false, List.of(), Map.of());
    }

    /** 호출 한 번(저장 전). in = 보낸 입력, chars = 입력 JSON 글자 수. */
    public record Call(Map<String, Object> in, LlmResult<AnswerOutput> r, int chars, ToolView view) {}

    /** A5 호출만(저장하지 않음). 도구 라운드는 흐름(pa/flow)이 돌리고, 끝에 save로 A6 단계들과 함께 한 번에 쓴다. */
    public Call call(CaseState st, List<FlowIn> flows, PackageOpener.Opened opened, PackageOpener.RefDate ref,
                     ToolView tv, Instant deadline, LlmGateway.Check<AnswerOutput> extra) {
        return call(st, flows, opened, ref, tv, deadline, extra, false);
    }

    /** discarded = 견주기용 호출(실제 호출 실행기, 저장하지 않음: llm_call_logs.discarded=1). */
    public Call call(CaseState st, List<FlowIn> flows, PackageOpener.Opened opened, PackageOpener.RefDate ref,
                     ToolView tv, Instant deadline, LlmGateway.Check<AnswerOutput> extra, boolean discarded) {
        KnowledgeSnapshot k = knowledge.snapshot();
        Map<String, Object> in = input(k, st, flows, opened, ref, tv);
        Set<String> rows = ledgerRowIds(st);
        Set<String> keys = allowedKeys(opened, tv);
        Set<String> omitted = omitted(opened, tv);
        LlmResult<AnswerOutput> r = llm.call(PROMPT, in, AnswerOutput.class,
                out -> {
                    String bad = check(k, known(st), flows, keys, rows, out, tv, omitted);
                    return bad != null || extra == null ? bad : extra.apply(out);
                },
                new CallContext(st.caseRow().caseId(), null, deadline, discarded));
        return new Call(in, r, json.writeValueAsString(in).length(), tv);
    }

    /** 저장하지 않고 답만 짓는다(견주기용). 실패한 호출이면 null. */
    public Answer preview(CaseState st, Call c, List<FlowIn> flows, PackageOpener.Opened opened, PackageOpener.RefDate ref) {
        return c.r().ok() ? answer(knowledge.snapshot(), st, flows, opened, ref, c.r().value(), List.of(),
                new Askable[1], c.view()) : null;
    }

    /** 인용해도 되는 키: 이번 꾸러미 + 도구가 글을 붙인 절. */
    public static Set<String> allowedKeys(PackageOpener.Opened opened, ToolView tv) {
        Set<String> out = new LinkedHashSet<>(opened.keys());
        out.addAll(tv.found().keySet());
        return out;
    }

    /** 입력 줄이기로 글을 뺀 꾸러미 절(도구로 읽은 것은 뺌). '해석' 역할로만 쓰인 안내서 절. */
    public static Set<String> omitted(PackageOpener.Opened opened, ToolView tv) {
        Set<String> out = new LinkedHashSet<>();
        if (!tv.reduce()) {
            return out;
        }
        for (PackageOpener.Source s : opened.sources().values()) {
            if (!PackageOpener.GUIDE_SECTION.equals(s.kind()) || tv.found().containsKey(s.key())) {
                continue;
            }
            boolean onlyInterp = true, any = false;
            for (PackageOpener.OpenedIssue oi : opened.issues()) {
                String role = oi.roles().get(s.key());
                if (role != null) {
                    any = true;
                    onlyInterp &= ROLE_INTERP.equals(role);
                }
            }
            if (any && onlyInterp) {
                out.add(s.key());
            }
        }
        return out;
    }

    static final String ROLE_INTERP = "해석";

    // ───────── 입력 ─────────

    static Set<String> ledgerRowIds(CaseState st) {
        Set<String> out = new LinkedHashSet<>();
        for (CaseState.Fact f : st.currentFacts()) {
            out.addAll(f.ledgerRows());
        }
        return out;
    }

    static boolean isLedgerFact(String factId) {
        return factId.matches("L\\d+");
    }

    Map<String, Object> input(KnowledgeSnapshot k, CaseState st, List<FlowIn> flows, PackageOpener.Opened opened,
                              PackageOpener.RefDate ref) {
        return input(k, st, flows, opened, ref, ToolView.NONE);
    }

    Map<String, Object> input(KnowledgeSnapshot k, CaseState st, List<FlowIn> flows, PackageOpener.Opened opened,
                              PackageOpener.RefDate ref, ToolView tv) {
        Set<String> omitted = omitted(opened, tv);
        // S8b: 사안과 무관한 사실 목록, 그다음 꾸러미 원문을 앞에(같은 꾸러미면 재시도·gap 질문 뒤 다시 보낼 때 앞부분이 같아 공급자 캐시가 맞게), 사실·쟁점은 뒤
        Map<String, Object> in = new LinkedHashMap<>();
        List<Map<String, Object>> ids = new ArrayList<>();
        for (Catalog.Fact f : k.catalog().facts()) {
            if (!isLedgerFact(f.factId())) {
                ids.add(Map.of("fact", f.factId(), "question", f.question()));
            }
        }
        in.put("fact_ids", ids);
        List<Map<String, Object>> src = new ArrayList<>();
        for (PackageOpener.Source s : opened.sources().values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", s.key());
            m.put("kind", s.kind());
            m.put("doc", s.docTitle());
            m.put("label", s.label());
            m.put("title", s.title());
            if (s.hasUpcoming()) {
                m.put("upcoming", s.upcoming().stream().map(u -> String.valueOf(u.effectiveFrom())).toList());
            }
            if (omitted.contains(s.key())) {
                m.put("text_omitted", true);   // S10 입력 줄이기: 글은 read_section으로
            } else {
                m.put("text", s.text());
            }
            src.add(m);
        }
        in.put("sources", src);
        in.put("text", st.caseRow().content());
        in.put("answers", st.turns().stream().filter(t -> "USER".equals(t.role()) && t.text() != null)
                .map(CaseState.Turn::text).toList());
        in.put("service_name", st.caseRow().serviceName());
        in.put("reference_date", String.valueOf(opened.date()));
        List<Map<String, Object>> common = new ArrayList<>();
        for (CaseState.Fact f : st.currentFacts()) {
            if (f.flowNo() == null) {
                common.add(fact(k, f));
            }
        }
        in.put("common_facts", common);
        List<Map<String, Object>> fl = new ArrayList<>();
        for (FlowIn f : flows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("flow", f.flow());
            st.flow(f.flow()).ifPresent(x -> {
                m.put("label", x.label());
                m.put("origin", x.origin());
                m.put("destination", x.destination());
                m.put("service_name", x.serviceName());
            });
            List<Map<String, Object>> facts = new ArrayList<>(), ledger = new ArrayList<>();
            for (CaseState.Fact x : st.currentFacts()) {
                if (x.flowNo() == null || x.flowNo() != f.flow()) {
                    continue;
                }
                if (isLedgerFact(x.factId())) {
                    Map<String, Object> l = new LinkedHashMap<>();
                    l.put("fact", x.factId());
                    l.put("looks_at", k.catalog().fact(x.factId()).map(Catalog.Fact::question).orElse(null));
                    l.put("status", x.status());
                    l.put("values", x.labels());
                    l.put("rows", x.ledgerRows());
                    ledger.add(l);
                } else {
                    facts.add(fact(k, x));
                }
            }
            m.put("facts", facts);
            m.put("ledger", ledger);
            Map<String, Object> slots = new LinkedHashMap<>();
            f.slots().forEach((s, v) -> slots.put(s, Map.of("status", v.status(), "values", v.values())));
            m.put("slots", slots);
            List<Map<String, Object>> issues = new ArrayList<>();
            for (IssueIn i : f.issues()) {
                Map<String, Object> im = new LinkedHashMap<>();
                im.put("issue", i.issue());
                im.put("package_id", i.packageId());
                im.put("grade", i.grade());
                im.put("rules", i.ruleIds());
                im.put("conditions", i.conditionIds());
                im.put("interp", interp(k, opened.packageVersion(), i));
                PackageOpener.OpenedIssue oi = openedIssue(opened, i);
                List<Map<String, Object>> keys = new ArrayList<>();
                if (oi != null) {
                    for (String key : oi.keys()) {
                        keys.add(Map.of("key", key, "role", oi.roles().getOrDefault(key, "")));
                    }
                }
                im.put("keys", keys);
                issues.add(im);
            }
            m.put("issues", issues);
            fl.add(m);
        }
        in.put("flows", fl);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String id : ledgerRowIds(st)) {
            k.ledger().row(id).ifPresent(row -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("row_id", id);
                r.put("attachment", row.attachment());
                r.put("text", row.text());
                rows.add(r);
            });
        }
        in.put("ledger_rows", rows);
        if (tv.enabled()) {
            // S10: 도구 결과는 맨 뒤(앞부분이 같아 다시 부를 때 공급자 캐시가 맞게)
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("remaining", tv.remaining());
            t.put("results", tv.results());
            in.put("tools", t);
        }
        return in;
    }

    static Map<String, Object> fact(KnowledgeSnapshot k, CaseState.Fact f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fact", f.factId());
        m.put("question", k.catalog().fact(f.factId()).map(Catalog.Fact::question).orElse(null));
        List<Map<String, Object>> vs = new ArrayList<>();
        for (CaseInput.Value v : f.values()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("label", v.label());
            if (v.detail() != null) {
                x.put("detail", v.detail());
            }
            vs.add(x);
        }
        m.put("values", vs);
        m.put("status", f.status());
        m.put("quote", f.basisQuote());
        return m;
    }

    /** 켜진 해석 쟁점의 물음(꾸러미 조건의 interp q). 'PKG14.I29' 꼴은 그 하위 꾸러미에서 찾는다. */
    static List<Map<String, Object>> interp(KnowledgeSnapshot k, String version, IssueIn i) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : i.interp()) {
            String pkg = i.packageId(), iid = id;
            int dot = id.indexOf('.');
            if (dot > 0) {
                pkg = id.substring(0, dot);
                iid = id.substring(dot + 1);
            }
            final String want = iid;
            String q = k.packages().get(version, pkg).map(p -> interpQ(p, want)).orElse(null);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("q", q);
            out.add(m);
        }
        return out;
    }

    static String interpQ(Packages.Package p, String id) {
        if (p.conditions() == null || !p.conditions().isArray()) {
            return null;
        }
        for (JsonNode c : p.conditions()) {
            JsonNode arr = c.get("interp");
            if (arr == null || !arr.isArray()) {
                continue;
            }
            for (JsonNode x : arr) {
                if (id.equals(x.path("id").asString(null))) {
                    return x.path("q").asString(null);
                }
            }
        }
        return null;
    }

    static PackageOpener.OpenedIssue openedIssue(PackageOpener.Opened o, IssueIn i) {
        for (PackageOpener.OpenedIssue oi : o.issues()) {
            if (oi.flow() != null && oi.flow() == i.flow() && oi.packageId().equals(i.packageId())) {
                return oi;
            }
        }
        return null;
    }

    // ───────── 업무 검사 ─────────

    /** 통과면 null. */
    /** 사실이 정해졌나(지금 판이 NOT_FOUND·UNKNOWN이 아님). 흐름이 비면 모든 흐름(흐름이 없으면 공통)에서 정해졌을 때. */
    public interface Known {
        boolean test(Integer flow, String fact);
    }

    static Known known(CaseState st) {
        return (flow, fact) -> {
            if (flow != null) {
                return decided(st.current(flow, fact));
            }
            if (st.flows().isEmpty()) {
                return decided(st.current(null, fact));
            }
            return st.flows().stream().allMatch(f -> decided(st.current(f.flowNo(), fact)));
        };
    }

    static boolean decided(java.util.Optional<CaseState.Fact> f) {
        return f.isPresent() && !"NOT_FOUND".equals(f.get().status()) && !"UNKNOWN".equals(f.get().status());
    }

    static String check(KnowledgeSnapshot k, Known known, List<FlowIn> flows, Set<String> keys, Set<String> rows,
                        AnswerOutput out) {
        return check(k, known, flows, keys, rows, out, ToolView.NONE, Set.of());
    }

    /** S10: 글을 뺀 절(omitted)을 읽지 않고 인용하면 거절, 도구 요청 꼴(search_guides는 검색어, read_section은 있는 절 ID). */
    static String check(KnowledgeSnapshot k, Known known, List<FlowIn> flows, Set<String> keys, Set<String> rows,
                        AnswerOutput out, ToolView tv, Set<String> omitted) {
        List<String> bad = new ArrayList<>();
        for (AnswerOutput.IssueOut o : out.issues()) {
            for (String c : o.cites()) {
                if (omitted.contains(c)) {
                    bad.add(o.flow() + "|" + o.issue() + ": 글을 받지 않은 절 '" + c + "' 인용(read_section으로 읽거나 인용에서 뺌)");
                }
            }
        }
        if (tv.enabled()) {
            for (AnswerOutput.ToolRequest q : out.toolRequests()) {
                if (TOOL_SEARCH.equals(q.tool())) {
                    if (q.query() == null || q.query().isBlank()) {
                        bad.add("search_guides에 검색어 없음");
                    }
                } else if (TOOL_READ.equals(q.tool())) {
                    if (q.sectionId() == null || !k.sections().has(q.sectionId())) {
                        bad.add("read_section 절 '" + q.sectionId() + "' 없음");
                    }
                } else {
                    bad.add("모르는 도구 '" + q.tool() + "'");
                }
            }
        }
        Map<String, Integer> seen = new LinkedHashMap<>();
        Set<String> want = new LinkedHashSet<>();
        for (FlowIn f : flows) {
            for (IssueIn i : f.issues()) {
                want.add(f.flow() + "|" + i.issue());
            }
        }
        for (AnswerOutput.IssueOut o : out.issues()) {
            String p = o.flow() + "|" + o.issue();
            if (!want.contains(p)) {
                bad.add("흐름 " + o.flow() + " 쟁점 '" + o.issue() + "'은 넘긴 쟁점이 아님");
                continue;
            }
            seen.merge(p, 1, Integer::sum);
            if (o.cites().isEmpty()) {
                bad.add(p + ": 인용 키 없음");
            }
            for (String c : o.cites()) {
                if (!keys.contains(c)) {
                    bad.add(p + ": 인용 키 '" + c + "'는 넘긴 꾸러미 밖");
                }
            }
            for (String r : o.ledgerRows()) {
                if (!rows.contains(r)) {
                    bad.add(p + ": 원장 행 '" + r + "'는 넘긴 후보 밖");
                }
            }
        }
        for (String p : want) {
            int n = seen.getOrDefault(p, 0);
            if (n != 1) {
                bad.add("쟁점 " + p + " 답이 " + n + "개(하나여야 함)");
            }
        }
        for (AnswerOutput.GapOut g : out.gaps()) {
            if (g.flow() != null && flows.stream().noneMatch(f -> f.flow() == g.flow())) {
                bad.add("gap 흐름 " + g.flow() + " 없음");
            }
            if (g.issue() != null && !g.issue().isBlank() && want.stream().noneMatch(p ->
                    p.endsWith("|" + g.issue()) && (g.flow() == null || p.startsWith(g.flow() + "|")))) {
                bad.add("gap 쟁점 '" + g.issue() + "' 없음");
            }
            boolean missing = AnswerOutput.FACT_MISSING.equals(g.kind());
            boolean detail = AnswerOutput.DETAIL_NEEDED.equals(g.kind());
            if ((missing || detail) && g.facts().isEmpty()) {
                bad.add(g.kind() + "인데 사실 ID 없음");
            }
            for (String f : g.facts()) {
                if (k.catalog().fact(f).isEmpty() || isLedgerFact(f)) {
                    bad.add("gap 사실 '" + f + "'는 사실 목록 밖");
                } else if (missing && known.test(g.flow(), f)) {
                    bad.add("FACT_MISSING 사실 '" + f + "'는 이미 정해짐(무엇인지 더 알아야 하면 DETAIL_NEEDED)");
                } else if (detail && !known.test(g.flow(), f)) {
                    bad.add("DETAIL_NEEDED 사실 '" + f + "'는 아직 모름(FACT_MISSING)");
                }
            }
        }
        return bad.isEmpty() ? null : String.join("; ", bad.subList(0, Math.min(4, bad.size())));
    }

    // ───────── 코드가 정하는 것 · 저장 ─────────

    /** 모델 출력 → 답(코드가 확정 여부·이관 사유·원문 요약을 정함). */
    static Answer answer(KnowledgeSnapshot k, CaseState st, List<FlowIn> flows, PackageOpener.Opened opened,
                         PackageOpener.RefDate ref, AnswerOutput out, List<Askable> askable, Askable[] asked) {
        return answer(k, st, flows, opened, ref, out, askable, asked, ToolView.NONE);
    }

    static Answer answer(KnowledgeSnapshot k, CaseState st, List<FlowIn> flows, PackageOpener.Opened opened,
                         PackageOpener.RefDate ref, AnswerOutput out, List<Askable> askable, Askable[] asked,
                         ToolView tv) {
        List<Answer.Gap> gaps = new ArrayList<>();
        List<Answer.AddedReason> reasons = baseReasons(ref);
        for (AnswerOutput.GapOut g : out.gaps()) {
            String handled = HANDLED_NONE;
            if (AnswerOutput.DETAIL_NEEDED.equals(g.kind())) {
                handled = HANDLED_NOTE;
            } else if (AnswerOutput.FACT_MISSING.equals(g.kind())) {
                Askable hit = asked[0] == null ? match(g, askable) : null;
                if (hit != null) {
                    asked[0] = hit;
                    handled = HANDLED_QUESTION;
                }
            } else {
                String code = AnswerOutput.INTERPRETATION.equals(g.kind()) ? Answer.GAP_INTERPRETATION
                        : Answer.GAP_OUTSIDE_PACKAGE;
                reasons.add(new Answer.AddedReason(code, g.flow(),
                        (g.issue() == null || g.issue().isBlank() ? "" : g.issue() + ": ") + g.note()));
                handled = HANDLED_REASON;
            }
            gaps.add(new Answer.Gap(g.kind(), g.flow(), blankToNull(g.issue()), g.facts(), g.note(), handled));
        }
        boolean caseGap = gaps.stream().anyMatch(g -> g.flow() == null && g.issue() == null
                && !AnswerOutput.DETAIL_NEEDED.equals(g.kind()));
        List<Answer.IssueAnswer> issues = new ArrayList<>();
        List<String> citeOrder = new ArrayList<>();
        Map<String, String> roleOf = new LinkedHashMap<>();
        List<String> rowOrder = new ArrayList<>();
        for (FlowIn f : flows) {
            for (IssueIn i : f.issues()) {
                AnswerOutput.IssueOut o = out.issues().stream()
                        .filter(x -> x.flow() == f.flow() && x.issue().equals(i.issue())).findFirst().orElseThrow();
                List<String> why = new ArrayList<>(), checks = new ArrayList<>();
                if (!o.conclusive()) {
                    why.add("모델이 미확정으로 냄");
                }
                if (!i.interp().isEmpty()) {
                    why.add("해석 쟁점 " + String.join("·", i.interp()));
                }
                for (Answer.Gap g : gaps) {
                    boolean mine = (g.flow() == null || g.flow() == f.flow())
                            && (g.issue() == null || g.issue().equals(i.issue()));
                    if (mine && AnswerOutput.DETAIL_NEEDED.equals(g.kind())) {
                        checks.add(String.join("·", g.facts()) + ": " + g.note());   // 확인 필요 표시만(확정 여부·출구 안 바꿈)
                    } else if (mine) {
                        why.add("gap " + g.kind() + (g.issue() == null ? "(사안 전체)" : ""));
                    }
                }
                boolean conclusive = why.isEmpty();
                issues.add(new Answer.IssueAnswer(f.flow(), i.issue(), i.packageId(), i.grade(), o.opinion(),
                        o.actions(), o.cites(), o.ledgerRows(), o.conclusive(), conclusive, List.copyOf(new LinkedHashSet<>(why)),
                        List.copyOf(checks)));
                PackageOpener.OpenedIssue oi = openedIssue(opened, i);
                for (String c : o.cites()) {
                    if (!citeOrder.contains(c)) {
                        citeOrder.add(c);
                    }
                    if (oi != null && oi.roles().containsKey(c)) {
                        roleOf.putIfAbsent(c, oi.roles().get(c));
                    }
                }
                o.ledgerRows().stream().filter(r -> !rowOrder.contains(r)).forEach(rowOrder::add);
            }
        }
        List<Answer.Source> sources = new ArrayList<>();
        for (String c : citeOrder) {
            PackageOpener.Source s = opened.sources().get(c);
            if (s != null) {
                sources.add(source(s, roleOf.get(c), EXCERPT));
            } else {
                // S10: A6이 글을 붙인 꾸러미 밖 안내서 절 → 출처 A6, 즉답 근거 아님('해석 참고')
                Answer.Source x = source(tv.found().get(c), ROLE_INTERP, EXCERPT);
                sources.add(new Answer.Source(x.key(), x.kind(), x.doc(), x.label(), x.title(), x.role(), x.textHash(),
                        x.effectiveFrom(), x.upcoming(), false, x.usePolicy(), x.excerpt(), Answer.Source.A6));
            }
        }
        List<Answer.LedgerRow> rows = new ArrayList<>();
        for (String id : rowOrder) {
            Ledger.Row r = k.ledger().row(id).orElse(null);
            if (r != null) {
                rows.add(new Answer.LedgerRow(id, r.attachment(), r.textHash(), excerpt(r.text(), EXCERPT)));
            }
        }
        boolean all = !caseGap && issues.stream().allMatch(Answer.IssueAnswer::conclusive);
        return new Answer(Answer.SPECIFIC, ref.caseDate() == null ? null : String.valueOf(ref.caseDate()),
                ref.basis(), String.valueOf(ref.openDate()), ref.past(), all, List.copyOf(issues), List.copyOf(gaps),
                null, null, null, List.copyOf(sources), List.copyOf(rows), null, reasons, null, null);
    }

    public static final String HANDLED_QUESTION = "QUESTION", HANDLED_REASON = "REASON", HANDLED_NONE = "NONE",
            HANDLED_NOTE = "NOTE";

    /** 사실 부족 gap의 사실 중 A2 후보에 있는 첫 것(후보 순서 = A2 순서). gap 흐름이 비면 어느 흐름이든. */
    static Askable match(AnswerOutput.GapOut g, List<Askable> askable) {
        for (Askable a : askable) {
            if (g.facts().contains(a.fact()) && (g.flow() == null || a.flow() == null || g.flow().equals(a.flow()))) {
                return a;
            }
        }
        return null;
    }

    /** 과거 기준일 → 이관(03 4.2 A4: 과거본은 받지 않음). */
    static List<Answer.AddedReason> baseReasons(PackageOpener.RefDate ref) {
        List<Answer.AddedReason> out = new ArrayList<>();
        if (ref.past()) {
            out.add(new Answer.AddedReason(Answer.PAST_REFERENCE_DATE, null,
                    "기준일 " + ref.caseDate() + "가 오늘(" + ref.openDate() + ")보다 앞: 과거 시점 사안"));
        }
        return out;
    }

    static Answer.Source source(PackageOpener.Source s, String role, int excerpt) {
        return new Answer.Source(s.key(), s.kind(), s.docTitle(), s.label(), s.title(), role, s.textHash(),
                s.effectiveFrom() == null ? null : String.valueOf(s.effectiveFrom()),
                s.upcoming().stream().map(u -> String.valueOf(u.effectiveFrom())).toList(), s.answerBasis(),
                s.usePolicy(), excerpt <= 0 ? s.text() : excerpt(s.text(), excerpt));
    }

    static String excerpt(String t, int n) {
        if (t == null) {
            return null;
        }
        String one = t.replaceAll("\\s+", " ").strip();
        return one.length() <= n ? one : one.substring(0, n) + "…";
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /**
     * 마지막 호출 c를 A5 단계로 쓴다. toolSteps(S10 A6 단계, actor TOOL)는 같은 트랜잭션에서 A5 단계 앞에 쓴다(도구 라운드 중간에 실패하면
     * 아무것도 남지 않아 재시도가 처음부터 다시 돈다: 같은 입력 뒤 같은 단계 저장 거절과 부딪치지 않게). callIds = 모든 라운드의 호출.
     * steps = [A5 단계, A6 단계…].
     */
    public Result save(CaseState st, Call c, List<FlowIn> flows, PackageOpener.Opened opened, PackageOpener.RefDate ref,
                       List<Askable> askable, List<Long> callIds, List<NewStep> toolSteps) {
        LlmResult<AnswerOutput> r = c.r();
        Map<String, Object> in = c.in();
        String caseId = st.caseRow().caseId();
        LocalDateTime at = clock.nowUtc();
        KnowledgeSnapshot k = knowledge.snapshot();
        Askable[] asked = new Askable[1];
        Answer a = r.ok() ? answer(k, st, flows, opened, ref, r.value(), askable, asked, c.view()) : null;
        return lock.write(caseId, st.inputMark(), STEP, NAME, null, () -> {
            List<Integer> tools = new ArrayList<>();
            for (NewStep t : toolSteps) {
                tools.add(store.addStep(t));
            }
            Object out = a != null ? a : Map.of("error", String.valueOf(r.lastError()));
            int seq = store.addStep(NewStep.llm(caseId, STEP, NAME, in, out, at, null, r.promptId(), r.promptVersion(),
                    r.model(), r.temperature(), code.value(), r.status().name(), r.attempts()));
            linkCalls(jdbc, caseId, seq, callIds);
            List<Integer> steps = new ArrayList<>();
            steps.add(seq);
            steps.addAll(tools);
            return new Result(r.status(), a, List.copyOf(steps), List.copyOf(callIds), r.ok() ? null : r.lastError(),
                    c.chars(), asked[0]);
        });
    }

    static void linkCalls(JdbcClient jdbc, String caseId, int seq, List<Long> callIds) {
        if (!callIds.isEmpty()) {
            jdbc.sql("UPDATE llm_call_logs SET step_seq=:seq WHERE case_id=:cid AND call_id IN (:ids)")
                    .param("seq", seq).param("cid", caseId).param("ids", callIds).update();
        }
    }
}
