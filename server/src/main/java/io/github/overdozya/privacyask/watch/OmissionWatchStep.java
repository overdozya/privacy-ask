package io.github.overdozya.privacyask.watch;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.overdozya.privacyask.common.AppClock;
import io.github.overdozya.privacyask.common.CodeVersion;
import io.github.overdozya.privacyask.knowledge.Knowledge;
import io.github.overdozya.privacyask.knowledge.Packages;
import io.github.overdozya.privacyask.llm.CallContext;
import io.github.overdozya.privacyask.llm.LlmGateway;
import io.github.overdozya.privacyask.llm.LlmResult;
import io.github.overdozya.privacyask.record.CaseInput.NewStep;
import io.github.overdozya.privacyask.record.CaseLock;
import io.github.overdozya.privacyask.record.CaseState;
import io.github.overdozya.privacyask.record.CaseStore;

/**
 * A3 누락 감시(06 3.3, 03 A3-4): 규칙이 흐름마다 찾은 쟁점을 받아, 놓친 쟁점·목록 밖 쟁점 신호를 LLM이 표시만 한다(A3_watch).
 * 쟁점을 더하지 않는다. 표시가 하나라도 있으면 watchFlag = 이관 사유 WATCH_FLAG(예비 출구는 S5가 모음).
 * 업무 검사: 근거 문구가 문의·현업 답 글에 글자 그대로 없음, MISSED 쟁점 이름이 사안 꾸러미 판 14개 밖, 이미 찾은 쟁점, 없는 흐름.
 */
public class OmissionWatchStep {
    public static final String STEP = "A3", PROMPT = "A3_watch", NAME = "누락 감시";
    static final String SEP = " … ";

    /** 규칙이 그 흐름에서 찾은 쟁점 이름(issue_packages.issue_name). S5 쟁점 도출 결과를 S7이 옮겨 준다. */
    public record FlowIssues(int flow, List<String> issues) {}

    public record Result(LlmResult.Status status, WatchFlags flags, boolean watchFlag, List<Integer> steps,
                         List<Long> callIds, String error) {
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

    public OmissionWatchStep(LlmGateway llm, CaseStore store, CaseLock lock, JdbcClient jdbc, Knowledge knowledge,
                             AppClock clock, CodeVersion code) {
        this.llm = llm;
        this.store = store;
        this.lock = lock;
        this.jdbc = jdbc;
        this.knowledge = knowledge;
        this.clock = clock;
        this.code = code;
    }

    public Result run(CaseState st, List<FlowIssues> found, Instant deadline) {
        Map<String, Object> in = input(st, found);
        List<String> catalog = catalog(st);
        LlmResult<WatchFlags> r = llm.call(PROMPT, in, WatchFlags.class, out -> check(st, found, catalog, out),
                new CallContext(st.caseRow().caseId(), null, deadline, false));
        return save(st, in, r);
    }

    /** 쟁점 목록 = 사안이 고정한 꾸러미 판의 쟁점 이름(package_id 순). */
    List<String> catalog(CaseState st) {
        return knowledge.snapshot().packages().inVersion(st.caseRow().packageVersion()).stream()
                .map(Packages.Package::issueName).toList();
    }

    static List<String> answers(CaseState st) {
        return st.turns().stream().filter(t -> "USER".equals(t.role()) && t.text() != null && !t.text().isBlank())
                .map(CaseState.Turn::text).toList();
    }

    Map<String, Object> input(CaseState st, List<FlowIssues> found) {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("text", st.caseRow().content());
        in.put("answers", answers(st));
        List<Map<String, Object>> flows = new ArrayList<>();
        for (CaseState.Flow f : st.flows()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("flow", f.flowNo());
            m.put("label", f.label());
            m.put("origin", f.origin());
            m.put("destination", f.destination());
            flows.add(m);
        }
        in.put("flows", flows);
        List<Map<String, Object>> fi = new ArrayList<>();
        for (FlowIssues x : found) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("flow", x.flow());
            m.put("issues", x.issues());
            fi.add(m);
        }
        in.put("found_issues", fi);
        in.put("catalog", catalog(st));
        return in;
    }

    /** 통과면 null. */
    static String check(CaseState st, List<FlowIssues> found, List<String> catalog, WatchFlags out) {
        List<String> texts = new ArrayList<>();
        texts.add(st.caseRow().content());
        texts.addAll(answers(st));
        List<String> bad = new ArrayList<>();
        for (WatchFlags.Flag f : out.flags()) {
            if (!quoted(texts, f.quote())) {
                bad.add(f.kind() + " 근거 문구 '" + f.quote() + "'가 문의·답 글에 없음");
            }
            if (WatchFlags.MISSED.equals(f.kind())) {
                if (f.issue() == null || !catalog.contains(f.issue())) {
                    bad.add("MISSED 쟁점 '" + f.issue() + "'는 쟁점 목록 밖");
                    continue;
                }
                if (f.flow() == null || st.flow(f.flow()).isEmpty()) {
                    bad.add("MISSED '" + f.issue() + "': 없는 흐름 " + f.flow());
                    continue;
                }
                Optional<FlowIssues> fi = found.stream().filter(x -> x.flow() == f.flow()).findFirst();
                if (fi.isPresent() && fi.get().issues().contains(f.issue())) {
                    bad.add("MISSED '" + f.issue() + "'는 흐름 " + f.flow() + "에서 이미 찾은 쟁점");
                }
            } else if (f.issue() != null && catalog.contains(f.issue())) {
                bad.add("OUTSIDE인데 쟁점 '" + f.issue() + "'는 목록 안(MISSED로)");
            }
        }
        return bad.isEmpty() ? null : String.join("; ", bad.subList(0, Math.min(3, bad.size())));
    }

    /** 조각(' … '로 이은 것 포함)이 모두 한 글 안에 있나. */
    static boolean quoted(List<String> texts, String quote) {
        if (quote == null || quote.isBlank()) {
            return false;
        }
        String[] pieces = quote.split(Pattern.quote(SEP), -1);
        for (String t : texts) {
            boolean all = true;
            for (String p : pieces) {
                if (p.isEmpty() || !t.contains(p)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    Result save(CaseState st, Map<String, Object> in, LlmResult<WatchFlags> r) {
        String caseId = st.caseRow().caseId();
        LocalDateTime at = clock.nowUtc();
        return lock.write(caseId, st.inputMark(), STEP, NAME, null, () -> {
            Object out;
            boolean flag = false;
            if (r.ok()) {
                WatchFlags w = r.value();
                flag = !w.flags().isEmpty();
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("flags", r.raw().get("flags"));
                o.put("missed_issue", w.missedIssue());
                o.put("outside_catalog", w.outsideCatalog());
                o.put("watch_flag", flag);
                out = o;
            } else {
                out = Map.of("error", String.valueOf(r.lastError()));
            }
            int seq = store.addStep(NewStep.llm(caseId, STEP, NAME, in, out, at, null, r.promptId(), r.promptVersion(),
                    r.model(), r.temperature(), code.value(), r.status().name(), r.attempts()));
            if (!r.callIds().isEmpty()) {
                jdbc.sql("UPDATE llm_call_logs SET step_seq=:seq WHERE case_id=:cid AND call_id IN (:ids)")
                        .param("seq", seq).param("cid", caseId).param("ids", r.callIds()).update();
            }
            return new Result(r.status(), r.ok() ? r.value() : null, flag, List.of(seq), r.callIds(),
                    r.ok() ? null : r.lastError());
        });
    }
}
