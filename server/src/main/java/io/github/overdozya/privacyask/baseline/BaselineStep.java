package io.github.overdozya.privacyask.baseline;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.overdozya.privacyask.baseline.LedgerCandidates.Candidates;
import io.github.overdozya.privacyask.baseline.LedgerCandidates.Group;
import io.github.overdozya.privacyask.baseline.LedgerVerdict.Verdict;
import io.github.overdozya.privacyask.common.AppClock;
import io.github.overdozya.privacyask.common.CodeVersion;
import io.github.overdozya.privacyask.knowledge.Knowledge;
import io.github.overdozya.privacyask.knowledge.KnowledgeSnapshot;
import io.github.overdozya.privacyask.knowledge.Ledger;
import io.github.overdozya.privacyask.llm.CallContext;
import io.github.overdozya.privacyask.llm.LlmGateway;
import io.github.overdozya.privacyask.llm.LlmResult;
import io.github.overdozya.privacyask.record.CaseInput.NewFact;
import io.github.overdozya.privacyask.record.CaseInput.NewStep;
import io.github.overdozya.privacyask.record.CaseInput.Value;
import io.github.overdozya.privacyask.record.CaseLock;
import io.github.overdozya.privacyask.record.CaseState;
import io.github.overdozya.privacyask.record.CaseStore;

/**
 * A3 기준선(06 3.3·3.4, 03 A3, 04 1.0): 후보 열거(코드) → 원장 행 인용(LLM, A3_ledger) → 판정(코드) → 원장 사실 L1~L4.
 * 단계 기록 셋: CODE "원장 후보 행 열거", LLM "원장 행 인용"(후보가 하나라도 있을 때만), CODE "기준선 판정".
 * 판정 단계는 늘 쓴다(서비스명이 없어 아무 사실도 안 쓴 때도): S7이 "원장 대조 했나"를 이 단계로 본다.
 */
public class BaselineStep {
    public static final String STEP = "A3", PROMPT = "A3_ledger";
    public static final String NAME_CANDIDATES = "원장 후보 행 열거", NAME_CITE = "원장 행 인용", NAME_VERDICT = "기준선 판정";

    public record Result(LlmResult.Status status, Candidates candidates, List<Verdict> verdicts, boolean llmCalled,
                         List<Integer> steps, List<Long> callIds, String error) {
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

    public BaselineStep(LlmGateway llm, CaseStore store, CaseLock lock, JdbcClient jdbc, Knowledge knowledge,
                        AppClock clock, CodeVersion code) {
        this.llm = llm;
        this.store = store;
        this.lock = lock;
        this.jdbc = jdbc;
        this.knowledge = knowledge;
        this.clock = clock;
        this.code = code;
    }

    /** 원장 사실이 아직 하나도 없는 흐름(기본 대상). */
    public List<Integer> pendingFlows(CaseState st) {
        List<String> ids = LedgerCandidates.ledgerFacts(knowledge.snapshot().catalog()).stream()
                .map(f -> f.factId()).toList();
        List<Integer> out = new ArrayList<>();
        for (CaseState.Flow f : st.flows()) {
            boolean has = st.currentFacts().stream()
                    .anyMatch(x -> f.flowNo() == (x.flowNo() == null ? -1 : x.flowNo()) && ids.contains(x.factId()));
            if (!has) {
                out.add(f.flowNo());
            }
        }
        return out;
    }

    public Result run(CaseState st, Instant deadline) {
        return run(st, pendingFlows(st), deadline);
    }

    public Result run(CaseState st, List<Integer> flows, Instant deadline) {
        KnowledgeSnapshot k = knowledge.snapshot();
        Candidates c = LedgerCandidates.enumerate(st, flows, k);
        LlmResult<LedgerCites> r = null;
        Map<String, Object> in = null;
        if (c.anyRows()) {
            in = input(st, c);
            r = llm.call(PROMPT, in, LedgerCites.class, out -> LedgerVerdict.check(c, out),
                    new CallContext(st.caseRow().caseId(), null, deadline, false));
        }
        return save(st, c, in, r);
    }

    static Map<String, Object> input(CaseState st, Candidates c) {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("text", st.caseRow().content());
        List<Map<String, Object>> flows = new ArrayList<>();
        java.util.Set<Integer> used = new java.util.TreeSet<>();
        c.groups().stream().filter(g -> !g.rows().isEmpty()).forEach(g -> used.addAll(g.flows()));
        for (int n : used) {
            CaseState.Flow f = st.flow(n).orElseThrow();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("flow", n);
            m.put("label", f.label());
            m.put("origin", f.origin());
            m.put("destination", f.destination());
            m.put("service_name", LedgerCandidates.serviceFor(st, f).orElse(null));
            flows.add(m);
        }
        in.put("flows", flows);
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Group g : c.groups()) {
            if (g.rows().isEmpty()) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fact", g.factId());
            m.put("looks_at", g.looksAt());
            m.put("allowed_match", g.allowedMatch());
            m.put("flows", g.flows());
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Ledger.Row r : g.rows()) {
                Map<String, Object> rm = new LinkedHashMap<>();
                rm.put("row_id", r.rowId());
                rm.put("attachment", r.attachment());
                Map<String, String> fields = new LinkedHashMap<>();
                for (Ledger.Field f : r.fields()) {
                    String name = f.name();
                    for (int i = 2; fields.containsKey(name); i++) {
                        name = f.name() + " (" + i + ")";
                    }
                    fields.put(name, f.text().replace('\n', ' '));
                }
                rm.put("fields", fields);
                rows.add(rm);
            }
            m.put("candidates", rows);
            groups.add(m);
        }
        in.put("groups", groups);
        return in;
    }

    static Map<String, Object> candidatesOutput(Candidates c) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ledger_version", c.ledgerVersion());
        List<Map<String, Object>> gs = new ArrayList<>();
        for (Group g : c.groups()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fact", g.factId());
            m.put("service", g.service());
            m.put("service_key", g.serviceKey());
            m.put("match_kind", g.kind().name());
            m.put("attachments", g.attachments());
            m.put("flows", g.flows());
            m.put("candidates", g.rowIds());
            gs.add(m);
        }
        out.put("groups", gs);
        out.put("no_service_flows", c.noService());
        return out;
    }

    Result save(CaseState st, Candidates c, Map<String, Object> llmIn, LlmResult<LedgerCites> r) {
        String caseId = st.caseRow().caseId();
        LocalDateTime at = clock.nowUtc();
        List<Long> callIds = r == null ? List.of() : r.callIds();
        return lock.write(caseId, st.inputMark(), STEP, NAME_VERDICT, null, () -> {
            List<Integer> steps = new ArrayList<>();
            Map<String, Object> candIn = new LinkedHashMap<>();
            candIn.put("flows", c.groups().stream().flatMap(g -> g.flows().stream()).distinct().sorted().toList());
            candIn.put("no_service_flows", c.noService());
            int s1 = store.addStep(NewStep.code(caseId, STEP, NAME_CANDIDATES, candIn, candidatesOutput(c), at, null,
                    code.value()));
            steps.add(s1);
            Integer s2 = null;
            if (r != null) {
                Object out = r.ok() ? r.raw() : Map.of("error", String.valueOf(r.lastError()));
                s2 = store.addStep(NewStep.llm(caseId, STEP, NAME_CITE, llmIn, out, at, null, r.promptId(),
                        r.promptVersion(), r.model(), r.temperature(), code.value(), r.status().name(), r.attempts()));
                steps.add(s2);
                link(caseId, s2, callIds);
                if (!r.ok()) {
                    return new Result(r.status(), c, List.of(), true, steps, callIds, r.lastError());
                }
            }
            List<Verdict> vs = LedgerVerdict.decide(c, r == null ? null : r.value(), knowledge.snapshot().catalog());
            Map<String, Object> vin = new LinkedHashMap<>();
            vin.put("candidates_from_step", s1);
            vin.put("cites_from_step", s2);
            Map<String, Object> vout = new LinkedHashMap<>();
            vout.put("check", "인용 행이 후보 안, 문구가 행 글 안(공백 뺀 비교)");
            vout.put("facts", vs.stream().map(BaselineStep::verdictMap).toList());
            if (!c.noService().isEmpty()) {
                vout.put("no_service_flows", c.noService());
                vout.put("note", "서비스명이 없는 흐름은 원장 대조를 하지 않음(F-S1을 먼저 묻는다)");
            }
            int s3 = store.addStep(NewStep.code(caseId, STEP, NAME_VERDICT, vin, vout, at, null, code.value()));
            steps.add(s3);
            for (Verdict v : vs) {
                store.addFact(new NewFact(caseId, v.factId(), List.of(Value.of(v.label())), v.status(), "LEDGER", s3, at,
                        v.flow(), null, v.quote(), v.rows(), c.ledgerVersion(), cut(v.note(), 500)));
            }
            return new Result(r == null ? LlmResult.Status.OK : r.status(), c, vs, r != null, steps, callIds, null);
        });
    }

    private static Map<String, Object> verdictMap(Verdict v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("flow", v.flow());
        m.put("fact", v.factId());
        m.put("values", List.of(v.label()));
        m.put("status", v.status());
        m.put("match", v.match());
        m.put("rows", v.rows());
        if (v.quote() != null) {
            m.put("quote", v.quote());
        }
        if (v.note() != null) {
            m.put("note", v.note());
        }
        return m;
    }

    private static String cut(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }

    private void link(String caseId, int seq, List<Long> callIds) {
        if (!callIds.isEmpty()) {
            jdbc.sql("UPDATE llm_call_logs SET step_seq=:seq WHERE case_id=:cid AND call_id IN (:ids)")
                    .param("seq", seq).param("cid", caseId).param("ids", callIds).update();
        }
    }
}
