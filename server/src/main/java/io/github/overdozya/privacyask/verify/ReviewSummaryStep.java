package io.github.overdozya.privacyask.verify;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.overdozya.privacyask.common.AppClock;
import io.github.overdozya.privacyask.common.CodeVersion;
import io.github.overdozya.privacyask.llm.CallContext;
import io.github.overdozya.privacyask.llm.LlmGateway;
import io.github.overdozya.privacyask.llm.LlmResult;
import io.github.overdozya.privacyask.record.CaseInput.NewStep;
import io.github.overdozya.privacyask.record.CaseLock;
import io.github.overdozya.privacyask.record.CaseState;
import io.github.overdozya.privacyask.record.CaseStore;

/**
 * A7 검토자용 요지(LLM, 프롬프트 A7_summary, thinking low). 검토 요청 묶음을 줄여 넘기고 요지 몇 줄만 받는다.
 * 출구·등급·쟁점은 코드가 이미 정했으므로 모델 출력은 글뿐이다. 업무 검사: 빈 줄 없음, 사유 코드 글자 그대로 쓰지 않음.
 */
public class ReviewSummaryStep {
    public static final String STEP = "A7", PROMPT = "A7_summary", NAME = "검토자 요지";

    public record SummaryOut(List<String> summary) {}

    public record Result(LlmResult.Status status, List<String> summary, List<Integer> steps, List<Long> callIds,
                         String error, int inputChars) {
        public boolean failed() {
            return status == LlmResult.Status.LLM_FAILED;
        }
    }

    private final LlmGateway llm;
    private final CaseStore store;
    private final CaseLock lock;
    private final JdbcClient jdbc;
    private final AppClock clock;
    private final CodeVersion code;

    public ReviewSummaryStep(LlmGateway llm, CaseStore store, CaseLock lock, JdbcClient jdbc, AppClock clock,
                             CodeVersion code) {
        this.llm = llm;
        this.store = store;
        this.lock = lock;
        this.jdbc = jdbc;
        this.clock = clock;
        this.code = code;
    }

    /** bundleStep = 묶음을 쓴 A7 검증·출구 단계 번호(단계 input에 남김). */
    public Result run(CaseState st, Map<String, Object> bundle, int bundleStep, Instant deadline) {
        Map<String, Object> in = input(bundle);
        String caseId = st.caseRow().caseId();
        LlmResult<SummaryOut> r = llm.call(PROMPT, in, SummaryOut.class, ReviewSummaryStep::check,
                new CallContext(caseId, null, deadline, false));
        int chars = in.toString().length();
        LocalDateTime at = clock.nowUtc();
        Map<String, Object> rec = new LinkedHashMap<>(in);
        rec.put("bundle_from_step", bundleStep);
        return lock.write(caseId, st.inputMark(), STEP, NAME, null, () -> {
            Object out = r.ok() ? Map.of("summary", r.value().summary()) : Map.of("error", String.valueOf(r.lastError()));
            int seq = store.addStep(NewStep.llm(caseId, STEP, NAME, rec, out, at, null, r.promptId(), r.promptVersion(),
                    r.model(), r.temperature(), code.value(), r.status().name(), r.attempts()));
            if (!r.callIds().isEmpty()) {
                jdbc.sql("UPDATE llm_call_logs SET step_seq=:seq WHERE case_id=:cid AND call_id IN (:ids)")
                        .param("seq", seq).param("cid", caseId).param("ids", r.callIds()).update();
            }
            return new Result(r.status(), r.ok() ? List.copyOf(r.value().summary()) : null, List.of(seq), r.callIds(),
                    r.ok() ? null : r.lastError(), chars);
        });
    }

    /** 통과면 null. */
    static String check(SummaryOut out) {
        List<String> bad = new ArrayList<>();
        if (out.summary() == null || out.summary().isEmpty()) {
            bad.add("요지 없음");
        } else {
            for (String s : out.summary()) {
                if (s == null || s.isBlank()) {
                    bad.add("빈 줄");
                } else if (s.matches(".*\\b[A-Z]+(_[A-Z]+)+\\b.*")) {
                    bad.add("사유 코드를 글자 그대로 씀: " + s.substring(0, Math.min(40, s.length())));
                }
            }
        }
        return bad.isEmpty() ? null : String.join("; ", bad.subList(0, Math.min(3, bad.size())));
    }

    /** 묶음에서 요지에 필요한 것만(원문·키 목록·해시는 빼서 입력을 작게). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> input(Map<String, Object> b) {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("content", b.get("content"));
        in.put("case_grade", b.get("case_grade"));
        List<Map<String, Object>> reasons = new ArrayList<>();
        for (Object o : (List<Object>) b.getOrDefault("exit_reasons", List.of())) {
            ExitAssembler.Reason r = (ExitAssembler.Reason) o;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", r.code());
            m.put("flow", r.flow());
            m.put("detail", r.detail());
            reasons.add(m);
        }
        in.put("exit_reasons", reasons);
        List<Map<String, Object>> flows = new ArrayList<>();
        for (Map<String, Object> f : (List<Map<String, Object>>) b.getOrDefault("flows", List.of())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("flow", f.get("flow"));
            m.put("label", f.get("label"));
            m.put("destination", f.get("destination"));
            m.put("grade", f.get("grade"));
            List<Map<String, Object>> issues = new ArrayList<>();
            for (Map<String, Object> i : (List<Map<String, Object>>) f.getOrDefault("issues", List.of())) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("issue", i.get("issue"));
                x.put("grade", i.get("grade"));
                x.put("opinion", i.get("opinion"));
                x.put("conclusive", i.get("conclusive"));
                x.put("checks", i.get("checks"));
                issues.add(x);
            }
            m.put("issues", issues);
            List<String> unknown = new ArrayList<>();
            ((Map<String, Map<String, Object>>) f.getOrDefault("slots", Map.of())).forEach((k, v) -> {
                if ("UNKNOWN".equals(v.get("status"))) {
                    unknown.add(k);
                }
            });
            m.put("unknown_slots", unknown);
            flows.add(m);
        }
        in.put("flows", flows);
        List<Map<String, Object>> rem = new ArrayList<>();
        for (Map<String, Object> q : (List<Map<String, Object>>) b.getOrDefault("remaining_questions", List.of())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("flow", q.get("flow"));
            m.put("question", q.get("question"));
            rem.add(m);
        }
        in.put("remaining", rem);
        if (b.get("gaps") instanceof List<?> g && !g.isEmpty()) {
            in.put("gaps", g);
        }
        if (b.get("general") != null) {
            in.put("general", b.get("general"));
        }
        return in;
    }
}
