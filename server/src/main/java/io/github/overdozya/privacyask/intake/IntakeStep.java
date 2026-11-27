package io.github.overdozya.privacyask.intake;

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
 * A0 입구(06 3.3·3.4): LLM이 신호 5종 + 근거 문구를 내고(A0_intake), IntakeJudge가 유형을 정한다.
 * 업무 검사: 근거 문구가 문의 원문에 글자 그대로 없으면 거절(→ 1회 다시 → LLM_FAILED).
 * call(LLM, DB 없음)과 save(짧은 tx, CaseLock)를 나눠 둔다: S7이 A0 ‖ A1을 같이 띄우고 차례로 저장한다(06 6.3).
 */
public class IntakeStep {
    public static final String STEP = "A0", PROMPT = "A0_intake";
    public static final String NAME_LLM = "입구 신호 표시", NAME_CODE = "입구 판정";
    static final String SEP = " … ";

    /** status LLM_FAILED면 decision·signals가 null. steps = 이번에 쓴 단계 번호. */
    public record Result(LlmResult.Status status, IntakeJudge.Decision decision, IntakeSignals signals,
                         List<Integer> steps, List<Long> callIds, String error) {
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

    public IntakeStep(LlmGateway llm, CaseStore store, CaseLock lock, JdbcClient jdbc, AppClock clock, CodeVersion code) {
        this.llm = llm;
        this.store = store;
        this.lock = lock;
        this.jdbc = jdbc;
        this.clock = clock;
        this.code = code;
    }

    public Result run(CaseState st, Instant deadline) {
        return save(st, call(st, deadline));
    }

    /** LLM 호출만(트랜잭션 밖). */
    public LlmResult<IntakeSignals> call(CaseState st, Instant deadline) {
        String content = st.caseRow().content();
        return llm.call(PROMPT, input(content), IntakeSignals.class, out -> check(content, out),
                new CallContext(st.caseRow().caseId(), null, deadline, false));
    }

    static Map<String, Object> input(String content) {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("text", content);
        return in;
    }

    /** 업무 검사(06 3.3 A0): 근거 문구가 문의 원문에 글자 그대로. 통과면 null. */
    static String check(String content, IntakeSignals out) {
        List<String> bad = new ArrayList<>();
        for (IntakeSignals.Signal s : out.signals()) {
            if (s.quote() == null || s.quote().isBlank()) {
                bad.add(s.kind() + " 근거 문구 없음");
                continue;
            }
            for (String piece : s.quote().split(java.util.regex.Pattern.quote(SEP), -1)) {
                if (piece.isEmpty() || !content.contains(piece)) {
                    bad.add(s.kind() + " '" + piece + "'가 문의 원문에 없음");
                }
            }
        }
        return bad.isEmpty() ? null : String.join("; ", bad.subList(0, Math.min(3, bad.size())));
    }

    /** 결과 저장: LLM 단계 + 코드 판정 단계 + cases.intake_type. LLM_FAILED면 실패 단계 행만. */
    public Result save(CaseState st, LlmResult<IntakeSignals> r) {
        String caseId = st.caseRow().caseId();
        CaseState.InputMark mark = st.inputMark();
        LocalDateTime at = clock.nowUtc();
        Map<String, Object> in = input(st.caseRow().content());
        if (!r.ok()) {
            return lock.write(caseId, mark, STEP, NAME_LLM, null, () -> {
                int seq = store.addStep(llmStep(caseId, in, Map.of("error", String.valueOf(r.lastError())), at, r));
                link(caseId, seq, r.callIds());
                return new Result(r.status(), null, null, List.of(seq), r.callIds(), r.lastError());
            });
        }
        IntakeJudge.Decision d = IntakeJudge.judge(r.value());
        return lock.write(caseId, mark, STEP, NAME_LLM, null, () -> {
            int s1 = store.addStep(llmStep(caseId, in, r.raw(), at, r));
            link(caseId, s1, r.callIds());
            Map<String, Object> codeIn = new LinkedHashMap<>();
            codeIn.put("signals_from_step", s1);
            Map<String, Object> codeOut = new LinkedHashMap<>();
            codeOut.put("intake_type", d.intakeType());
            codeOut.put("out_of_scope_kind", d.outOfScopeKind());
            codeOut.put("reason", d.reason());
            codeOut.put("law_names", d.lawNames());
            codeOut.put("signal_counts", d.signalCounts());
            codeOut.put("rule", IntakeJudge.RULE);
            int s2 = store.addStep(NewStep.code(caseId, STEP, NAME_CODE, codeIn, codeOut, at, null, code.value()));
            Map<String, Object> set = new LinkedHashMap<>();
            set.put("intake_type", d.intakeType());
            set.put("out_of_scope_kind", d.outOfScopeKind());
            store.updateCase(caseId, at, set);
            return new Result(r.status(), d, r.value(), List.of(s1, s2), r.callIds(), null);
        });
    }

    private NewStep llmStep(String caseId, Object in, Object out, LocalDateTime at, LlmResult<?> r) {
        return NewStep.llm(caseId, STEP, NAME_LLM, in, out, at, null, r.promptId(), r.promptVersion(), r.model(),
                r.temperature(), code.value(), r.status().name(), r.attempts());
    }

    /** 호출 기록을 단계 번호에 잇는다(seq는 저장 때 정해지므로 호출 때는 비워 둔다). */
    private void link(String caseId, int seq, List<Long> callIds) {
        if (!callIds.isEmpty()) {
            jdbc.sql("UPDATE llm_call_logs SET step_seq=:seq WHERE case_id=:cid AND call_id IN (:ids)")
                    .param("seq", seq).param("cid", caseId).param("ids", callIds).update();
        }
    }
}
