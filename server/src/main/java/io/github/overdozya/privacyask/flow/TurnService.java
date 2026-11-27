package io.github.overdozya.privacyask.flow;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.overdozya.privacyask.baseline.BaselineStep;
import io.github.overdozya.privacyask.common.AppClock;
import io.github.overdozya.privacyask.common.CodeVersion;
import io.github.overdozya.privacyask.facts.AnswerApplier;
import io.github.overdozya.privacyask.facts.FactExtractStep;
import io.github.overdozya.privacyask.facts.FactOutput;
import io.github.overdozya.privacyask.intake.IntakeJudge;
import io.github.overdozya.privacyask.intake.IntakeSignals;
import io.github.overdozya.privacyask.intake.IntakeStep;
import io.github.overdozya.privacyask.issues.CaseJudge;
import io.github.overdozya.privacyask.issues.IssueDeriver;
import io.github.overdozya.privacyask.issues.JudgeService;
import io.github.overdozya.privacyask.issues.PreliminaryExit;
import io.github.overdozya.privacyask.knowledge.Catalog;
import io.github.overdozya.privacyask.knowledge.Knowledge;
import io.github.overdozya.privacyask.knowledge.KnowledgeSnapshot;
import io.github.overdozya.privacyask.llm.LlmResult;
import io.github.overdozya.privacyask.question.QuestionSelector;
import io.github.overdozya.privacyask.question.QuestionService;
import io.github.overdozya.privacyask.question.QuestionText;
import io.github.overdozya.privacyask.record.CaseInput.NewCase;
import io.github.overdozya.privacyask.record.CaseInput.NewStep;
import io.github.overdozya.privacyask.record.CaseInput.NewTurn;
import io.github.overdozya.privacyask.record.CaseLock;
import io.github.overdozya.privacyask.record.CaseNotFound;
import io.github.overdozya.privacyask.record.CaseReader;
import io.github.overdozya.privacyask.record.CaseState;
import io.github.overdozya.privacyask.record.CaseStore;
import io.github.overdozya.privacyask.record.Rejected;
import io.github.overdozya.privacyask.record.TurnConflict;
import io.github.overdozya.privacyask.watch.OmissionWatchStep;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 한 턴(06 3.4): 사용자 입력을 먼저 커밋하고, DB 상태에서 다음 할 일(NextAction)을 계산해 끝까지 진행한다.
 * LLM 호출은 트랜잭션 밖, 결과는 각 단계가 짧은 tx(CaseLock)로 쓴다. 요청마다 turn_logs 한 행(06 6.4).
 * 첫 요청은 A0 ‖ A1 추측 실행(06 6.3): A0이 SPECIFIC이면 A1 결과를 그대로 쓰고, 아니면 버린다(llm_call_logs.discarded=1).
 */
public class TurnService {
    private static final Logger log = LoggerFactory.getLogger(TurnService.class);
    public static final String KIND_CREATE = "CREATE", KIND_ANSWER = "ANSWER", KIND_RETRY = "RETRY";
    static final int MAX_ACTIONS = 40;

    private final CaseStore store;
    private final CaseReader reader;
    private final CaseLock lock;
    private final JdbcClient jdbc;
    private final Knowledge knowledge;
    private final IntakeStep intake;
    private final FactExtractStep facts;
    private final AnswerApplier answers;
    private final BaselineStep baseline;
    private final OmissionWatchStep watch;
    private final JudgeService judges;
    private final QuestionService questions;
    private final AppClock clock;
    private final CodeVersion code;
    private final FlowProperties props;
    private final TurnLogWriter turnLogs;
    private final ExecutorService exec;
    private final JsonMapper json;

    public TurnService(CaseStore store, CaseReader reader, CaseLock lock, JdbcClient jdbc, Knowledge knowledge,
                       IntakeStep intake, FactExtractStep facts, AnswerApplier answers, BaselineStep baseline,
                       OmissionWatchStep watch, JudgeService judges, QuestionService questions, AppClock clock,
                       CodeVersion code, FlowProperties props, TurnLogWriter turnLogs, ExecutorService exec,
                       JsonMapper json) {
        this.store = store;
        this.reader = reader;
        this.lock = lock;
        this.jdbc = jdbc;
        this.knowledge = knowledge;
        this.intake = intake;
        this.facts = facts;
        this.answers = answers;
        this.baseline = baseline;
        this.watch = watch;
        this.judges = judges;
        this.questions = questions;
        this.clock = clock;
        this.code = code;
        this.props = props;
        this.turnLogs = turnLogs;
        this.exec = exec;
        this.json = json;
    }

    /** 요청 하나의 문맥. */
    static final class Ctx {
        final TurnMeter meter;
        final Instant deadline;
        String caseId;
        Integer turnNo;
        final List<Integer> steps = new ArrayList<>();
        List<String> a0LawNames;      // 이번 요청에서 A0을 돌렸으면 그 (마) 이름
        OmissionWatchStep.Result watched;
        List<String> lawNames = List.of(), lawSkipped = List.of();

        Ctx(TurnMeter meter, Instant deadline) {
            this.meter = meter;
            this.deadline = deadline;
        }
    }

    // ───────── API 1·2·3 ─────────

    /** API 1: 사안 생성(판 셋 고정) → 진행. */
    public TurnResult create(String requester, String content, String serviceName) {
        return metered(KIND_CREATE, null, ctx -> {
            if (content == null || content.isBlank()) {
                throw FlowException.of(400, FlowException.BAD_REQUEST, "content가 비었음");
            }
            String svc = serviceName == null || serviceName.isBlank() ? null : serviceName.strip();
            if (svc != null && svc.length() > 200) {
                throw FlowException.of(400, FlowException.BAD_REQUEST, "service_name은 200자까지");
            }
            KnowledgeSnapshot k = knowledge.snapshot();
            String caseId = UUID.randomUUID().toString();
            store.addCase(new NewCase(caseId, requester, content.strip(), svc, k.catalog().ruleSetVersion(),
                    k.packages().currentVersion(), k.ledger().latestVersion().orElse(null), clock.nowUtc()));
            ctx.caseId = caseId;
            ctx.turnNo = 0;
            return advance(ctx);
        });
    }

    /** API 2: 답 턴 커밋 → 진행. 허용 값 밖이면 저장하지 않고 422 + 같은 질문. */
    public TurnResult answer(String requester, String caseId, Integer questionTurn, List<String> choices, String detail,
                             String text) {
        return metered(KIND_ANSWER, caseId, ctx -> {
            CaseState st = loadOwned(requester, caseId);
            if (!"ASKING".equals(st.caseRow().state())) {
                throw new FlowException(409, FlowException.NOT_ASKING, "닫힌 사안(" + st.caseRow().state() + ")",
                        Map.of("case_id", caseId), null);
            }
            Optional<CaseState.Turn> last = st.lastTurn().filter(CaseState.Turn::isQuestion);
            if (last.isEmpty() || questionTurn == null || last.get().turnNo() != questionTurn) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("case_id", caseId);
                d.put("question_turn", questionTurn);
                d.put("open_question_turn", last.map(CaseState.Turn::turnNo).orElse(null));
                throw new FlowException(409, FlowException.NOT_THE_QUESTION, "마지막 질문이 아닌 턴에 답함", d, null);
            }
            CaseState.Turn q = last.get();
            List<String> ch = choices == null ? List.of() : List.copyOf(choices);
            String bad = invalidAnswer(q.factId(), ch, detail, text);
            if (bad != null) {
                throw new FlowException(422, FlowException.INVALID_ANSWER, bad, Map.of("case_id", caseId),
                        question(q));
            }
            String turnText = AnswerText.compose(ch, ch.isEmpty() ? null : detail, text);
            LocalDateTime at = clock.nowUtc();
            int turnNo = lock.writeInput(caseId, st.inputMark(),
                    () -> store.addTurn(NewTurn.answer(caseId, q.stepSeq(), q.turnNo(), turnText, ch, at)));
            ctx.turnNo = turnNo;
            return advance(ctx);
        });
    }

    /** API 3: LLM_FAILED 뒤(또는 언제든) 같은 NextAction을 다시. 할 일이 없으면 지금 질문·결과를 그대로. */
    public TurnResult retry(String requester, String caseId) {
        return metered(KIND_RETRY, caseId, ctx -> {
            loadOwned(requester, caseId);
            return advance(ctx);
        });
    }

    String invalidAnswer(String factId, List<String> choices, String detail, String text) {
        if (choices.isEmpty()) {
            if (text == null || text.isBlank()) {
                return "보기도 글도 없음";
            }
            if (detail != null && !detail.isBlank()) {
                return "내용(detail)은 '예(나라)'처럼 내용을 받는 보기를 고를 때만";
            }
            return null;
        }
        String bad = answers.invalidReason(factId, choices, detail);
        if (bad != null) {
            return bad;
        }
        if (detail != null && !detail.isBlank()) {
            Catalog.Fact f = knowledge.snapshot().catalog().fact(factId).orElseThrow();
            boolean takes = choices.stream().anyMatch(c -> f.option(c).map(o -> "DETAIL".equals(o.kind())).orElse(false));
            if (!takes) {
                return "내용(detail)은 '예(나라)'처럼 내용을 받는 보기를 고를 때만";
            }
        }
        return null;
    }

    // ───────── 측정·오류 ─────────

    private TurnResult metered(String kind, String caseId, Function<Ctx, TurnResult> body) {
        TurnMeter m = new TurnMeter();
        Ctx ctx = new Ctx(m, clock.now().plus(props.turnTimeout()));
        ctx.caseId = caseId;
        TurnResult out = null;
        FlowException err = null;
        try (TurnMeter.Scope s = m.bind()) {
            out = body.apply(ctx);
        } catch (FlowException e) {
            err = e;
        } catch (TurnConflict e) {
            err = new FlowException(409, FlowException.TURN_CONFLICT, e.getMessage(),
                    details(ctx, "reason", e.reason().name()), null);
        } catch (CaseNotFound e) {
            err = new FlowException(404, FlowException.CASE_NOT_FOUND, "사안 없음", Map.of("case_id", String.valueOf(ctx.caseId)), null);
        } catch (Rejected e) {
            log.error("저장 함수 거부(코드 결함) {}", e.getMessage());
            err = new FlowException(500, FlowException.RECORD_REJECTED, e.getMessage(),
                    details(ctx, "rule", e.code()), null);
        }
        TurnResult.Timing t = new TurnResult.Timing(m.totalMs(), m.llmCalls(), m.llmMs(), m.llmDepth(), m.dbStatements());
        String result = err != null ? err.code() : "OK_" + out.outcome();
        if (ctx.caseId != null && (err == null || !FlowException.CASE_NOT_FOUND.equals(err.code()))) {
            try {
                turnLogs.write(ctx.caseId, ctx.turnNo, kind, t, m, ctx.steps, result, clock.nowUtc());
            } catch (RuntimeException e) {
                log.warn("turn_logs 쓰기 실패 {}", e.toString());
            }
        }
        if (err != null) {
            throw err;
        }
        return new TurnResult(out.caseId(), out.state(), out.outcome(), out.intake(), out.question(), out.result(),
                List.copyOf(ctx.steps), t);
    }

    private static Map<String, Object> details(Ctx ctx, String k, Object v) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("case_id", ctx.caseId);
        d.put("turn_no", ctx.turnNo);
        d.put(k, v);
        return d;
    }

    private FlowException llmFailed(Ctx ctx, NextAction a, String error) {
        Map<String, Object> d = details(ctx, "action", a.name());
        d.put("error", error);
        return new FlowException(503, FlowException.LLM_FAILED, "LLM 단계 실패: " + a, d, null);
    }

    private void checkDeadline(Ctx ctx) {
        if (clock.now().isAfter(ctx.deadline)) {
            throw new FlowException(503, FlowException.TURN_TIMEOUT, "한 턴 상한 " + props.turnTimeout() + " 넘음",
                    details(ctx, "path", ctx.meter.path()), null);
        }
    }

    CaseState loadOwned(String requester, String caseId) {
        Optional<CaseState> st = reader.load(caseId);
        if (st.isEmpty() || !st.get().caseRow().requesterId().equals(requester)) {
            throw new FlowException(404, FlowException.CASE_NOT_FOUND, "사안 없음", Map.of("case_id", caseId), null);
        }
        return st.get();
    }

    // ───────── 진행 ─────────

    TurnResult advance(Ctx ctx) {
        for (int i = 0; i < MAX_ACTIONS; i++) {
            checkDeadline(ctx);
            CaseState st = reader.load(ctx.caseId).orElseThrow(() -> new CaseNotFound(ctx.caseId));
            ctx.turnNo = st.inputMark().turnNo();
            NextAction a = NextAction.decide(st, baseline);
            ctx.meter.step(a.name());
            switch (a) {
                case A0 -> intake(ctx, st);
                case CLOSE_OUT_OF_SCOPE -> {
                    LocalDateTime at = clock.nowUtc();
                    lock.writeInput(ctx.caseId, st.inputMark(), () -> {
                        Map<String, Object> set = new LinkedHashMap<>();
                        set.put("state", "TRANSFERRED");
                        set.put("closed_at", at);
                        store.updateCase(ctx.caseId, at, set);
                        return null;
                    });
                }
                case A1_INQUIRY -> {
                    FactExtractStep.Result r = llmStage(ctx, () -> facts.runInquiry(st, ctx.deadline),
                            FactExtractStep.Result::callIds);
                    ctx.steps.addAll(r.steps());
                    if (r.failed()) {
                        throw llmFailed(ctx, a, r.error());
                    }
                }
                case A1_CHOICE -> {
                    CaseState.Turn t = st.lastUserTurn().orElseThrow();
                    AnswerText.Parsed p = AnswerText.parse(t.choices(), t.text());
                    ctx.steps.addAll(answers.apply(st, t.turnNo(), p.detail()).steps());
                }
                case A1_FREE -> {
                    int turn = st.lastUserTurn().orElseThrow().turnNo();
                    FactExtractStep.Result r = llmStage(ctx, () -> facts.runAnswer(st, turn, ctx.deadline),
                            FactExtractStep.Result::callIds);
                    ctx.steps.addAll(r.steps());
                    if (r.failed()) {
                        throw llmFailed(ctx, a, r.error());
                    }
                }
                case A3_BASELINE -> {
                    BaselineStep.Result r = llmStage(ctx, () -> baseline.run(st, ctx.deadline),
                            BaselineStep.Result::callIds);
                    ctx.steps.addAll(r.steps());
                    if (r.failed()) {
                        throw llmFailed(ctx, a, r.error());
                    }
                }
                case A2 -> {
                    TurnResult q = a2(ctx, st);
                    if (q != null) {
                        return q;
                    }
                }
                case A3_WATCH -> {
                    List<PreliminaryExit.Reason> external = lawReasons(ctx, st);
                    CaseJudge.Judgment j = judges.judge(st, external);
                    List<OmissionWatchStep.FlowIssues> found = j.flows().stream()
                            .map(f -> new OmissionWatchStep.FlowIssues(f.flowNo(),
                                    f.issues().stream().map(IssueDeriver.Issue::issueName).distinct().toList()))
                            .toList();
                    OmissionWatchStep.Result r = llmStage(ctx, () -> watch.run(st, found, ctx.deadline),
                            OmissionWatchStep.Result::callIds);
                    ctx.steps.addAll(r.steps());
                    if (r.failed()) {
                        throw llmFailed(ctx, a, r.error());
                    }
                    ctx.watched = r;
                }
                case A3_EXIT -> {
                    return exit(ctx, st);
                }
                case END_GENERAL, END_TRANSFERRED -> {
                    return base(st, a == NextAction.END_GENERAL ? TurnResult.GENERAL : TurnResult.TRANSFERRED,
                            null, null);
                }
                case END_QUESTION -> {
                    return base(st, TurnResult.QUESTION, question(st.lastTurn().orElseThrow()), null);
                }
                case END_RESULT -> {
                    return base(st, TurnResult.RESULT, null, storedResult(ctx.caseId));
                }
            }
        }
        throw new IllegalStateException("NextAction이 " + MAX_ACTIONS + "번 안에 끝나지 않음: " + ctx.meter.path());
    }

    /** LLM 단계 하나: 벽시계 시간과 시도 수를 측정에 더한다(LLM을 안 불렀으면 0, 깊이도 안 늘림). */
    private <R> R llmStage(Ctx ctx, java.util.function.Supplier<R> run, Function<R, List<Long>> callIds) {
        long t0 = System.nanoTime();
        R r = run.get();
        ctx.meter.llmStage(System.nanoTime() - t0, callIds.apply(r).size());
        return r;
    }

    // ───────── A0 ‖ A1 ─────────

    private void intake(Ctx ctx, CaseState st) {
        boolean spec = props.speculativeA1() && st.lastUserTurn().isEmpty() && st.flows().isEmpty()
                && !st.doneSinceInput(FactExtractStep.STEP, FactExtractStep.NAME_INQUIRY, null);
        long t0 = System.nanoTime();
        CompletableFuture<LlmResult<FactOutput>> a1 = null;
        if (spec) {
            var task = ctx.meter.wrap(() -> facts.call(st, FactExtractStep.Source.INQUIRY, null, ctx.deadline));
            a1 = CompletableFuture.supplyAsync(() -> {
                try {
                    return task.call();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }, exec);
        }
        LlmResult<IntakeSignals> a0;
        try {
            a0 = intake.call(st, ctx.deadline);
        } catch (RuntimeException e) {
            if (a1 != null) {
                discardWhenDone(a1);
            }
            throw e;
        }
        IntakeStep.Result r0 = intake.save(st, a0);
        ctx.steps.addAll(r0.steps());
        boolean specific = !r0.failed() && IntakeJudge.SPECIFIC.equals(r0.decision().intakeType());
        if (!specific) {
            ctx.meter.llmStage(System.nanoTime() - t0, a0.callIds().size());
            if (a1 != null) {
                ctx.meter.step("A1_DISCARDED");
                discardWhenDone(a1);
            }
            if (r0.failed()) {
                throw llmFailed(ctx, NextAction.A0, r0.error());
            }
            ctx.a0LawNames = r0.decision().lawNames();
            return;
        }
        ctx.a0LawNames = r0.decision().lawNames();
        if (a1 == null) {
            ctx.meter.llmStage(System.nanoTime() - t0, a0.callIds().size());
            return;
        }
        LlmResult<FactOutput> a1r = await(ctx, a1);
        ctx.meter.llmStage(System.nanoTime() - t0, a0.callIds().size() + a1r.callIds().size());
        // A1을 띄울 때 읽은 상태로 저장한다: A0 저장은 intake_type만 바꾸므로 입력 표시(InputMark)·흐름·서비스명이 같다
        FactExtractStep.Result r1 = facts.save(st, FactExtractStep.Source.INQUIRY, null, a1r);
        ctx.steps.addAll(r1.steps());
        if (r1.failed()) {
            throw llmFailed(ctx, NextAction.A1_INQUIRY, r1.error());
        }
    }

    private <T> T await(Ctx ctx, CompletableFuture<T> f) {
        long ms = Math.max(1, ctx.deadline.toEpochMilli() - clock.now().toEpochMilli());
        try {
            return f.get(ms, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            discardWhenDone(f);
            throw new FlowException(503, FlowException.TURN_TIMEOUT, "추측 A1이 턴 상한 안에 안 끝남",
                    details(ctx, "path", ctx.meter.path()), null);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** 버린 추측 호출: 끝나는 대로 그 시도들을 discarded=1로(06 6.3). steps에는 남기지 않는다. */
    private void discardWhenDone(CompletableFuture<?> f) {
        f.whenComplete((r, e) -> {
            if (r instanceof LlmResult<?> lr && !lr.callIds().isEmpty()) {
                markDiscarded(lr.callIds());
            } else if (e != null) {
                log.warn("버린 추측 A1이 예외로 끝남 {}", e.toString());
            }
        });
    }

    int markDiscarded(List<Long> callIds) {
        return jdbc.sql("UPDATE llm_call_logs SET discarded=1 WHERE call_id IN (:ids)").param("ids", callIds).update();
    }

    // ───────── A2 · A3 쟁점 · 예비 출구 ─────────

    private TurnResult a2(Ctx ctx, CaseState st) {
        String caseId = ctx.caseId;
        List<PreliminaryExit.Reason> external = lawReasons(ctx, st);
        Set<Integer> cloud = facts.cloudItFlows(caseId);
        QuestionSelector.Selection sel = questions.next(st, cloud, external);
        ctx.meter.a2(sel.considered(), sel.evaluations(), sel.micros());
        Optional<QuestionSelector.Ask> ask = sel.ask();
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("order", questions.selector().order().name());
        in.put("cloud_flows", cloud);
        in.put("external", external.stream().map(TurnResult::reason).toList());
        putLaw(in, ctx);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ask", ask.map(TurnService::askMap).orElse(null));
        out.put("why", ask.map(QuestionSelector.Ask::why).orElse("쟁점·등급·출구를 바꿀 남은 사실 없음"));
        out.put("worth", sel.openKeys());
        out.put("considered", sel.considered());
        out.put("evaluations", sel.evaluations());
        out.put("micros", sel.micros());
        out.put("impact", ask.map(a -> a.impact().name()).orElse(null));
        if (sel.capped()) {
            out.put("capped", "사안당 질문 상한 " + questions.selector().maxPerCase() + "에 닿아 그만 묻는다");
        }
        LocalDateTime at = clock.nowUtc();
        CaseState.InputMark mark = st.inputMark();
        if (ask.isPresent()) {
            QuestionSelector.Ask a = ask.get();
            QuestionText.Text qt = questions.text(st, a);
            int[] w = lock.write(caseId, mark, "A2", NextAction.A2_NAME, null, () -> {
                int seq = store.addStep(NewStep.code(caseId, "A2", NextAction.A2_NAME, in, out, at, null, code.value()));
                int turn = store.addTurn(NewTurn.question(caseId, seq, a.flowNo(), a.factId(), a.askNo(), qt.text(),
                        qt.choices(), at));
                return new int[] {seq, turn};
            });
            ctx.steps.add(w[0]);
            CaseState.CaseRow c = st.caseRow();
            return new TurnResult(caseId, c.state(), TurnResult.QUESTION, new TurnResult.Intake(c.intakeType(),
                    c.outOfScopeKind()), new TurnResult.Question(w[1], a.flowNo(), a.factId(), a.askNo(), qt.text(),
                    qt.choices()), null, null, null);
        }
        CaseJudge.Judgment j = judges.judge(st, external);
        Map<String, Object> jin = new LinkedHashMap<>();
        jin.put("external", external.stream().map(TurnResult::reason).toList());
        TurnResult.Result jout = TurnResult.result(j, false);
        int[] w = lock.write(caseId, mark, "A2", NextAction.A2_NAME, null, () -> {
            int s1 = store.addStep(NewStep.code(caseId, "A2", NextAction.A2_NAME, in, out, at, null, code.value()));
            jin.put("after_step", s1);
            int s2 = store.addStep(NewStep.code(caseId, "A3", NextAction.A3_JUDGE_NAME, jin, jout, at, null,
                    code.value()));
            return new int[] {s1, s2};
        });
        ctx.steps.add(w[0]);
        ctx.steps.add(w[1]);
        return null;
    }

    private static Map<String, Object> askMap(QuestionSelector.Ask a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("flow", a.flowNo());
        m.put("fact", a.factId());
        m.put("ask_no", a.askNo());
        return m;
    }

    private TurnResult exit(Ctx ctx, CaseState st) {
        String caseId = ctx.caseId;
        List<PreliminaryExit.Reason> external = new ArrayList<>(lawReasons(ctx, st));
        List<String> flags = watchFlags(ctx);
        boolean wf = !flags.isEmpty();
        if (wf) {
            external.add(new PreliminaryExit.Reason(PreliminaryExit.WATCH_FLAG, null, String.join("; ", flags)));
        }
        CaseJudge.Judgment j = judges.judge(st, external);
        // 질문 상한으로 멈췄으면 남은 후보(그 밖이면 비어 있음): 검토자가 묻지 않은 사실을 알게
        List<String> unasked = questions.next(st, facts.cloudItFlows(caseId), lawReasons(ctx, st)).openKeys();
        TurnResult.Result r = TurnResult.result(j, wf, unasked);
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("external", external.stream().map(TurnResult::reason).toList());
        putLaw(in, ctx);
        in.put("rule", "이관 사유가 있으면 TRANSFER, 없으면 exit_policy[사안 등급](06 3.3 예비 출구)");
        LocalDateTime at = clock.nowUtc();
        int seq = lock.write(caseId, st.inputMark(), "A3", NextAction.A3_EXIT_NAME, null,
                () -> store.addStep(NewStep.code(caseId, "A3", NextAction.A3_EXIT_NAME, in, r, at, null, code.value())));
        ctx.steps.add(seq);
        return base(st, TurnResult.RESULT, null, r);
    }

    /** 누락 감시 표시 글(이번 요청에서 돌렸으면 그 결과, 아니면 마지막 감시 단계 출력). 비면 표시 없음. */
    private List<String> watchFlags(Ctx ctx) {
        List<String> out = new ArrayList<>();
        if (ctx.watched != null && ctx.watched.flags() != null) {
            ctx.watched.flags().flags().forEach(f -> out.add(flagText(f.kind(), f.issue(), f.flow())));
            return out;
        }
        JsonNode o = lastOutput(ctx.caseId, OmissionWatchStep.STEP, OmissionWatchStep.NAME);
        if (o != null && o.has("flags") && o.get("flags").isArray()) {
            for (JsonNode f : o.get("flags")) {
                out.add(flagText(f.path("kind").asString(""), f.path("issue").asString(null),
                        f.hasNonNull("flow") ? f.get("flow").asInt() : null));
            }
        }
        return out;
    }

    private static String flagText(String kind, String issue, Integer flow) {
        return kind + (issue == null || issue.isBlank() ? "" : " " + issue) + (flow == null ? "" : "(흐름 " + flow + ")");
    }

    /**
     * 적용 법령(06 3.3, 메인 결정): A0 (마) 이름 + F-B2 '예(법령·조문)'의 자유 입력 글을 모아 LawScopeJudge로 → OUTSIDE_KB_LAW 사유.
     */
    List<PreliminaryExit.Reason> lawReasons(Ctx ctx, CaseState st) {
        List<String> names = lawNames(ctx, st);
        var r = judges.lawScope().check(names);
        ctx.lawNames = names;
        ctx.lawSkipped = r.skipped();
        return r.reasons();
    }

    /** 단계 input에 남기는 법령 이름(모은 것·법령 꼴이 아니라 건너뛴 것, S7b). */
    private static void putLaw(Map<String, Object> in, Ctx ctx) {
        in.put("law_names", ctx.lawNames);
        in.put("law_skipped", ctx.lawSkipped);
    }

    List<String> lawNames(Ctx ctx, CaseState st) {
        Set<String> names = new LinkedHashSet<>();
        if (ctx.a0LawNames == null) {
            JsonNode o = lastOutput(ctx.caseId, IntakeStep.STEP, IntakeStep.NAME_CODE);
            List<String> got = new ArrayList<>();
            if (o != null && o.has("law_names")) {
                o.get("law_names").forEach(n -> got.add(n.asString()));
            }
            ctx.a0LawNames = got;
        }
        names.addAll(ctx.a0LawNames);
        for (CaseState.Fact f : st.currentFacts()) {
            if (LAW_FACT.equals(f.factId())) {
                f.values().stream().map(v -> v.detail()).filter(d -> d != null && !d.isBlank()).forEach(names::add);
            }
        }
        return List.copyOf(names);
    }

    static final String LAW_FACT = "F-B2";

    /** 같은 이름의 마지막 OK 단계 출력(JSON 한 칸, 1문장). */
    JsonNode lastOutput(String caseId, String step, String name) {
        return jdbc.sql("""
                SELECT output_json FROM steps WHERE case_id=? AND step=? AND name=? AND status IN ('OK','RETRY_OK')
                ORDER BY seq DESC LIMIT 1""").params(caseId, step, name).query(String.class).optional()
                .map(json::readTree).orElse(null);
    }

    TurnResult.Result storedResult(String caseId) {
        JsonNode o = lastOutput(caseId, "A3", NextAction.A3_EXIT_NAME);
        return o == null ? null : json.treeToValue(o, TurnResult.Result.class);
    }

    // ───────── 응답 짓기 ─────────

    static TurnResult base(CaseState st, String outcome, TurnResult.Question q, TurnResult.Result r) {
        CaseState.CaseRow c = st.caseRow();
        String state = TurnResult.TRANSFERRED.equals(outcome) ? "TRANSFERRED" : c.state();
        return new TurnResult(c.caseId(), state, outcome, new TurnResult.Intake(c.intakeType(), c.outOfScopeKind()),
                q, r, null, null);
    }

    static TurnResult.Question question(CaseState.Turn t) {
        return new TurnResult.Question(t.turnNo(), t.flowNo(), t.factId(), t.askNo() == null ? 1 : t.askNo(), t.text(),
                t.choices());
    }

    /**
     * 지금 상태 보기(API 4, 쓰지 않음): 열린 질문 · 마지막 예비 결과 · 입구 결과. 아직 할 일이 남았으면(LLM_FAILED 뒤 등) outcome PENDING과
     * 다음 할 일 이름.
     */
    public record Peek(String outcome, String nextAction, TurnResult.Question question, TurnResult.Result result) {}

    public Peek peek(CaseState st) {
        NextAction a = NextAction.decide(st, baseline);
        return switch (a) {
            case END_QUESTION -> new Peek(TurnResult.QUESTION, null, question(st.lastTurn().orElseThrow()), null);
            case END_RESULT -> new Peek(TurnResult.RESULT, null, null, storedResult(st.caseRow().caseId()));
            case END_GENERAL -> new Peek(TurnResult.GENERAL, null, null, null);
            case END_TRANSFERRED -> new Peek(TurnResult.TRANSFERRED, null, null, null);
            default -> new Peek("PENDING", a.name(), null, null);
        };
    }
}
