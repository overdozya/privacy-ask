package io.github.overdozya.privacyask.facts;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.overdozya.privacyask.common.AppClock;
import io.github.overdozya.privacyask.common.CodeVersion;
import io.github.overdozya.privacyask.facts.FactOutput.FactOut;
import io.github.overdozya.privacyask.facts.FactOutput.FlowOut;
import io.github.overdozya.privacyask.facts.FactOutput.ValueOut;
import io.github.overdozya.privacyask.knowledge.Catalog;
import io.github.overdozya.privacyask.knowledge.Knowledge;
import io.github.overdozya.privacyask.knowledge.KnowledgeSnapshot;
import io.github.overdozya.privacyask.llm.CallContext;
import io.github.overdozya.privacyask.llm.LlmGateway;
import io.github.overdozya.privacyask.llm.LlmResult;
import io.github.overdozya.privacyask.record.CaseInput.NewFact;
import io.github.overdozya.privacyask.record.CaseInput.NewFlow;
import io.github.overdozya.privacyask.record.CaseInput.NewStep;
import io.github.overdozya.privacyask.record.CaseInput.Value;
import io.github.overdozya.privacyask.record.CaseLock;
import io.github.overdozya.privacyask.record.CaseState;
import io.github.overdozya.privacyask.record.CaseStore;
import io.github.overdozya.privacyask.record.Rejected;

/**
 * A1 사실 추출(06 3.3, 03 A1): 문의 글(INQUIRY) 또는 현업 자유 답(ANSWER)에서 흐름과 사실을 뽑는다(A1_facts).
 * 업무 검사 = ① 모양(흐름 번호, 사실 ID, 공통·흐름, 원장 사실·'잘 모르겠음' 금지) ② CaseStore.checkFact를 저장 없이
 * (되돌리는 트랜잭션 안에서 임시 단계·흐름을 넣고 사실마다 검사: C02·C03·C07·C08·C09·C10). 하나라도 거부면 1회 다시.
 * 안 낸 사실은 행을 쓰지 않는다(= NOT_FOUND). 공통 사실 F-S1·F-S3은 cases.service_name·reference_date에도 옮긴다.
 */
public class FactExtractStep {
    public static final String STEP = "A1", PROMPT = "A1_facts";
    public static final String NAME_INQUIRY = "사실 추출(문의)", NAME_ANSWER = "사실 추출(답)";
    static final String NAME_DRY = "사실 추출(검사)";
    public static final String SERVICE_FACT = "F-S1", DATE_FACT = "F-S3";

    public enum Source { INQUIRY, ANSWER }

    /** 이번에 쓴 것. facts = 저장한 (흐름, 사실) 수, flowsAdded = 새 흐름 번호. */
    public record Result(LlmResult.Status status, FactOutput output, List<Integer> flowsAdded, int factRows,
                         List<Integer> steps, List<Long> callIds, String error) {
        public boolean failed() {
            return status == LlmResult.Status.LLM_FAILED;
        }
    }

    private final LlmGateway llm;
    private final CaseStore store;
    private final CaseLock lock;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Knowledge knowledge;
    private final AppClock clock;
    private final CodeVersion code;
    private volatile Map.Entry<KnowledgeSnapshot, FactSheet> sheet;

    public FactExtractStep(LlmGateway llm, CaseStore store, CaseLock lock, JdbcClient jdbc, TransactionTemplate tx,
                           Knowledge knowledge, AppClock clock, CodeVersion code) {
        this.llm = llm;
        this.store = store;
        this.lock = lock;
        this.jdbc = jdbc;
        this.tx = tx;
        this.knowledge = knowledge;
        this.clock = clock;
        this.code = code;
    }

    /** 첫 문의. */
    public Result runInquiry(CaseState st, Instant deadline) {
        return save(st, Source.INQUIRY, null, call(st, Source.INQUIRY, null, deadline));
    }

    /** 현업 자유 답(answerTurn = 그 ANSWER 턴 번호). */
    public Result runAnswer(CaseState st, int answerTurn, Instant deadline) {
        return save(st, Source.ANSWER, answerTurn, call(st, Source.ANSWER, answerTurn, deadline));
    }

    public LlmResult<FactOutput> call(CaseState st, Source src, Integer answerTurn, Instant deadline) {
        Map<String, Object> in = input(st, src, answerTurn);
        return llm.call(PROMPT, in, FactOutput.class, out -> check(st, src, answerTurn, out),
                new CallContext(st.caseRow().caseId(), null, deadline, false));
    }

    FactSheet sheet() {
        KnowledgeSnapshot snap = knowledge.snapshot();
        Map.Entry<KnowledgeSnapshot, FactSheet> s = sheet;
        if (s == null || s.getKey() != snap) {
            s = Map.entry(snap, FactSheet.of(snap.catalog()));
            sheet = s;
        }
        return s.getValue();
    }

    Map<String, Object> input(CaseState st, Source src, Integer answerTurn) {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("source", src.name());
        in.put("today", clock.seoulToday().toString());
        if (src == Source.ANSWER) {
            CaseState.Turn ans = answerTurn(st, answerTurn);
            CaseState.Turn q = ans.replyTo() == null ? null : st.turn(ans.replyTo()).orElse(null);
            if (q != null) {
                Map<String, Object> qm = new LinkedHashMap<>();
                qm.put("flow", q.flowNo());
                qm.put("fact", q.factId());
                qm.put("text", q.text());
                in.put("question", qm);
            }
            in.put("text", ans.text());
            List<Map<String, Object>> known = new ArrayList<>();
            for (CaseState.Flow f : st.flows()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("flow", f.flowNo());
                m.put("label", f.label());
                m.put("origin", f.origin());
                m.put("destination", f.destination());
                if (f.serviceName() != null) {
                    m.put("service_name", f.serviceName());
                }
                known.add(m);
            }
            in.put("known_flows", known);
        } else {
            in.put("text", st.caseRow().content());
        }
        in.put("facts", sheet().entries());
        return in;
    }

    private static CaseState.Turn answerTurn(CaseState st, Integer answerTurn) {
        if (answerTurn == null) {
            throw new IllegalArgumentException("ANSWER 출처는 답 턴 번호가 필요하다");
        }
        CaseState.Turn t = st.turn(answerTurn).orElseThrow(() -> new IllegalArgumentException("없는 턴 " + answerTurn));
        if (!"ANSWER".equals(t.kind())) {
            throw new IllegalArgumentException("턴 " + answerTurn + "은 답 턴이 아님");
        }
        return t;
    }

    // ───────── 업무 검사 ─────────

    /** 통과면 null, 거절이면 이유(최대 3개). */
    String check(CaseState st, Source src, Integer answerTurn, FactOutput out) {
        List<String> bad = shape(st, src, out);
        if (bad.isEmpty()) {
            bad = dryRun(st, src, answerTurn, out);
        }
        return bad.isEmpty() ? null : String.join("; ", bad.subList(0, Math.min(3, bad.size())));
    }

    /** ① 모양 검사(DB 없음). */
    List<String> shape(CaseState st, Source src, FactOutput out) {
        Catalog cat = knowledge.snapshot().catalog();
        List<String> bad = new ArrayList<>();
        Set<Integer> known = new TreeSet<>();
        st.flows().forEach(f -> known.add(f.flowNo()));
        int next = known.isEmpty() ? 1 : ((TreeSet<Integer>) known).last() + 1;
        Set<Integer> declared = new HashSet<>();
        for (FlowOut f : out.flows()) {
            if (known.contains(f.flow())) {
                bad.add("흐름 " + f.flow() + "은 이미 있음(새 흐름만 낸다)");
            } else if (f.flow() != next + declared.size()) {
                bad.add("흐름 번호 " + f.flow() + "가 차례(" + (next + declared.size()) + ")와 다름");
            }
            declared.add(f.flow());
        }
        if (src == Source.INQUIRY && known.isEmpty() && out.flows().isEmpty()) {
            bad.add("첫 문의에 흐름이 없음");
        }
        Set<String> pairs = new HashSet<>();
        for (FactOut f : out.facts()) {
            Optional<Catalog.Fact> c = cat.fact(f.fact());
            if (c.isEmpty()) {
                bad.add("없는 사실 ID " + f.fact());
                continue;
            }
            if (FactSheet.ledgerOnly(c.get())) {
                bad.add(f.fact() + "는 원장 사실(A3가 정함)");
                continue;
            }
            if (c.get().common() && !f.flows().isEmpty()) {
                bad.add(f.fact() + "는 공통 사실인데 흐름 " + f.flows());
            }
            if (!c.get().common() && f.flows().isEmpty()) {
                bad.add(f.fact() + "는 흐름 사실인데 흐름 없음");
            }
            for (Integer n : f.flows()) {
                if (!known.contains(n) && !declared.contains(n)) {
                    bad.add(f.fact() + ": 없는 흐름 " + n);
                }
            }
            for (ValueOut v : f.values()) {
                c.get().option(v.label()).filter(o -> "UNKNOWN".equals(o.kind()))
                        .ifPresent(o -> bad.add(f.fact() + " '" + v.label() + "': 미확인은 현업이 고를 때만"));
            }
            for (Integer n : f.flows().isEmpty() ? java.util.Collections.<Integer>singletonList(null) : f.flows()) {
                if (!pairs.add(f.fact() + "/" + n)) {
                    bad.add(f.fact() + " 흐름 " + n + " 두 번");
                }
            }
        }
        return bad;
    }

    /** ② CaseStore 사실 검사를 저장 없이: 되돌리는 트랜잭션 안에 임시 단계·흐름을 넣고 사실마다 checkFact. */
    List<String> dryRun(CaseState st, Source src, Integer answerTurn, FactOutput out) {
        String caseId = st.caseRow().caseId();
        LocalDateTime at = clock.nowUtc();
        List<String> bad = tx.execute(status -> {
            status.setRollbackOnly();
            // 사안 잠금: 같은 사안의 다른 쓰기와 단계 번호(MAX+1)가 엇갈리지 않게(되돌리므로 잠깐만 잡는다)
            jdbc.sql("SELECT case_id FROM cases WHERE case_id=? FOR UPDATE").param(caseId).query(String.class).optional();
            List<String> errs = new ArrayList<>();
            int seq;
            try {
                seq = store.addStep(NewStep.code(caseId, STEP, NAME_DRY, Map.of(), Map.of(), at, null, code.value()));
                for (FlowOut f : out.flows()) {
                    store.addFlow(newFlow(caseId, f, seq));
                }
            } catch (Rejected e) {
                errs.add(e.code() + " " + e.detail());
                return errs;
            }
            for (NewFact f : facts(caseId, src, answerTurn, out, seq, at)) {
                try {
                    store.checkFact(f);
                } catch (Rejected e) {
                    errs.add(e.code() + " " + e.detail());
                }
            }
            return errs;
        });
        return bad == null ? List.of() : bad;
    }

    private static NewFlow newFlow(String caseId, FlowOut f, int seq) {
        return new NewFlow(caseId, f.flow(), f.label(), f.origin(), f.destination(), blankToNull(f.serviceName()), seq);
    }

    /** 출력 사실 하나를 흐름마다 NewFact로 편다(공통 사실은 흐름 null 하나). */
    static List<NewFact> facts(String caseId, Source src, Integer answerTurn, FactOutput out, int seq, LocalDateTime at) {
        List<NewFact> list = new ArrayList<>();
        for (FactOut f : out.facts()) {
            List<Value> vals = f.values().stream().map(v -> new Value(v.label(), blankToNull(v.detail()))).toList();
            List<Integer> flows = f.flows().isEmpty() ? java.util.Collections.singletonList(null) : f.flows();
            for (Integer n : flows) {
                list.add(new NewFact(caseId, f.fact(), vals, f.status(), src.name(), seq, at, n,
                        src == Source.ANSWER ? answerTurn : null, f.quote(), List.of(), null, null));
            }
        }
        return list;
    }

    // ───────── 저장 ─────────

    public Result save(CaseState st, Source src, Integer answerTurn, LlmResult<FactOutput> r) {
        String caseId = st.caseRow().caseId();
        CaseState.InputMark mark = st.inputMark();
        LocalDateTime at = clock.nowUtc();
        String name = src == Source.INQUIRY ? NAME_INQUIRY : NAME_ANSWER;
        Map<String, Object> in = input(st, src, answerTurn);
        if (!r.ok()) {
            return lock.write(caseId, mark, STEP, name, null, () -> {
                int seq = store.addStep(llmStep(caseId, name, in, Map.of("error", String.valueOf(r.lastError())), at, r));
                link(caseId, seq, r.callIds());
                return new Result(r.status(), null, List.of(), 0, List.of(seq), r.callIds(), r.lastError());
            });
        }
        FactOutput out = r.value();
        return lock.write(caseId, mark, STEP, name, null, () -> {
            int seq = store.addStep(llmStep(caseId, name, in, r.raw(), at, r));
            link(caseId, seq, r.callIds());
            List<Integer> added = new ArrayList<>();
            for (FlowOut f : out.flows()) {
                store.addFlow(newFlow(caseId, f, seq));
                added.add(f.flow());
            }
            List<NewFact> facts = facts(caseId, src, answerTurn, out, seq, at);
            for (NewFact f : facts) {
                store.addFact(f);
            }
            store.updateCase(caseId, at, caseFields(st, out));
            return new Result(r.status(), out, added, facts.size(), List.of(seq), r.callIds(), null);
        });
    }

    /** F-S1 서비스명(사안에 아직 없을 때) → service_name, F-S3 날짜 → reference_date(INQUIRY), '이미 하는 중' → 오늘(TODAY). */
    Map<String, Object> caseFields(CaseState st, FactOutput out) {
        Map<String, Object> set = new LinkedHashMap<>();
        for (FactOut f : out.facts()) {
            if (f.values().isEmpty()) {
                continue;
            }
            ValueOut v = f.values().get(0);
            if (SERVICE_FACT.equals(f.fact()) && st.caseRow().serviceName() == null && v.detail() != null
                    && !v.detail().isBlank()) {
                set.put("service_name", v.detail().strip());
            }
            if (DATE_FACT.equals(f.fact())) {
                if (v.detail() != null) {
                    try {
                        set.put("reference_date", LocalDate.parse(v.detail().strip()));
                        set.put("reference_date_basis", "INQUIRY");
                    } catch (DateTimeParseException e) {
                        // 날짜 꼴이 아니면 기준일을 정하지 않는다(A4가 오늘로 둠, 04 F-S3)
                    }
                } else if ("이미 하는 중".equals(v.label())) {
                    set.put("reference_date", clock.seoulToday());
                    set.put("reference_date_basis", "TODAY");
                }
            }
        }
        return set;
    }

    private NewStep llmStep(String caseId, String name, Object in, Object out, LocalDateTime at, LlmResult<?> r) {
        return NewStep.llm(caseId, STEP, name, in, out, at, null, r.promptId(), r.promptVersion(), r.model(),
                r.temperature(), code.value(), r.status().name(), r.attempts());
    }

    private void link(String caseId, int seq, List<Long> callIds) {
        if (!callIds.isEmpty()) {
            jdbc.sql("UPDATE llm_call_logs SET step_seq=:seq WHERE case_id=:cid AND call_id IN (:ids)")
                    .param("seq", seq).param("cid", caseId).param("ids", callIds).update();
        }
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
