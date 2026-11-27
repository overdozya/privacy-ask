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

import io.github.overdozya.privacyask.answer.Answer;
import io.github.overdozya.privacyask.answer.GeneralAnswerStep;
import io.github.overdozya.privacyask.answer.AnswerOutput;
import io.github.overdozya.privacyask.answer.SpecificAnswerStep;
import io.github.overdozya.privacyask.tools.GuideTools;
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
import io.github.overdozya.privacyask.packages.PackageOpener;
import io.github.overdozya.privacyask.packages.PackageService;
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
import io.github.overdozya.privacyask.rules.ExitPolicy;
import io.github.overdozya.privacyask.verify.CitationVerifier;
import io.github.overdozya.privacyask.verify.ExitAssembler;
import io.github.overdozya.privacyask.verify.JudgmentReader;
import io.github.overdozya.privacyask.verify.JudgmentRecorder;
import io.github.overdozya.privacyask.verify.ReviewBundleBuilder;
import io.github.overdozya.privacyask.verify.ReviewSummaryStep;
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
    private final AnswerStages stages;

    /** S8: A4 꾸러미 열기·A5 답변(구체·일반). gapQuestions = 사안당 A5 사실 부족 gap으로 더 묻는 질문 수 상한. */
    public record AnswerStages(PackageService packages, SpecificAnswerStep specific, GeneralAnswerStep general,
                               int gapQuestions, GuideTools tools) {
        public AnswerStages(PackageService packages, SpecificAnswerStep specific, GeneralAnswerStep general) {
            this(packages, specific, general, 1, null);
        }

        public AnswerStages(PackageService packages, SpecificAnswerStep specific, GeneralAnswerStep general,
                            int gapQuestions) {
            this(packages, specific, general, gapQuestions, null);
        }
    }

    /** S9: A7 검토자 요지·A8 판정 기록·판정 읽기. */
    public record VerifyStages(ReviewSummaryStep summary, JudgmentRecorder recorder, JudgmentReader reader) {}

    private final VerifyStages verify;

    public TurnService(CaseStore store, CaseReader reader, CaseLock lock, JdbcClient jdbc, Knowledge knowledge,
                       IntakeStep intake, FactExtractStep facts, AnswerApplier answers, BaselineStep baseline,
                       OmissionWatchStep watch, JudgeService judges, QuestionService questions, AppClock clock,
                       CodeVersion code, FlowProperties props, TurnLogWriter turnLogs, ExecutorService exec,
                       JsonMapper json, AnswerStages stages, VerifyStages verify) {
        this.verify = verify;
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
        this.stages = stages;
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
        Opening opening;               // 이번 요청에서 A4를 돌렸으면 그 결과(A5가 그대로 씀)

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
                case A3_EXIT -> exit(ctx, st);
                case A4 -> a4(ctx, st);
                case A5_SPECIFIC -> {
                    TurnResult q = a5(ctx, st, a);
                    if (q != null) {
                        return q;
                    }
                }
                case A5_GENERAL -> {
                    GeneralAnswerStep.Result r = llmStage(ctx, () -> stages.general().run(st,
                            stages.packages().articleList(clock.seoulToday()), ctx.deadline), GeneralAnswerStep.Result::callIds);
                    ctx.steps.addAll(r.steps());
                    if (r.failed()) {
                        throw llmFailed(ctx, a, r.error());
                    }
                }
                case A5_GENERAL_TEXT -> {
                    Answer picked = generalPicks(ctx.caseId);
                    GeneralAnswerStep.TextResult r = llmStage(ctx, () -> stages.general().runText(st, picked,
                            ctx.deadline), GeneralAnswerStep.TextResult::callIds);
                    ctx.steps.addAll(r.steps());
                    if (r.failed()) {
                        throw llmFailed(ctx, a, r.error());
                    }
                }
                case A7 -> a7(ctx, st);
                case A8 -> a8(ctx, st);
                case END_GENERAL -> {
                    return base(st, TurnResult.GENERAL, null, finalOr(ctx.caseId, () -> storedGeneral(ctx.caseId)));
                }
                case END_TRANSFERRED -> {
                    return base(st, TurnResult.TRANSFERRED, null, null);
                }
                case END_QUESTION -> {
                    return base(st, TurnResult.QUESTION, question(st.lastTurn().orElseThrow()), null);
                }
                case END_RESULT -> {
                    return base(st, TurnResult.RESULT, null, finalOr(ctx.caseId, () -> storedResult(ctx.caseId)));
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
        out.put("raised", ask.map(QuestionSelector.Ask::raised).orElse(null));
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

    /** 예비 출구 단계를 쓴다(S8부터 끝내지 않고 A4·A5로 이어 감). */
    private void exit(Ctx ctx, CaseState st) {
        String caseId = ctx.caseId;
        List<PreliminaryExit.Reason> external = externalReasons(ctx, st);
        boolean wf = external.stream().anyMatch(x -> PreliminaryExit.WATCH_FLAG.equals(x.code()));
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
    }

    /** 출구에 넣는 바깥 사유: 적용 법령(OUTSIDE_KB_LAW) + 누락 감시 표시(WATCH_FLAG). 예비 출구와 A7·A8이 같은 것을 쓴다. */
    List<PreliminaryExit.Reason> externalReasons(Ctx ctx, CaseState st) {
        List<PreliminaryExit.Reason> external = new ArrayList<>(lawReasons(ctx, st));
        List<String> flags = watchFlags(ctx);
        if (!flags.isEmpty()) {
            external.add(new PreliminaryExit.Reason(PreliminaryExit.WATCH_FLAG, null, String.join("; ", flags)));
        }
        return external;
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

    /** 마지막 예비 출구 결과 + 그 뒤 A5 답(있으면, S8). 답이 더한 이관 사유는 출구에도 들어간다. */
    TurnResult.Result storedResult(String caseId) {
        JsonNode o = lastOutput(caseId, "A3", NextAction.A3_EXIT_NAME);
        if (o == null) {
            return null;
        }
        TurnResult.Result r = json.treeToValue(o, TurnResult.Result.class);
        Answer a = answerAfter(caseId, "A3", NextAction.A3_EXIT_NAME, SpecificAnswerStep.STEP, SpecificAnswerStep.NAME);
        return r.withAnswer(a);
    }

    Answer generalPicks(String caseId) {
        JsonNode o = lastOutput(caseId, GeneralAnswerStep.STEP, GeneralAnswerStep.NAME);
        return o == null ? null : json.treeToValue(o, Answer.class);
    }

    /** 일반 문의 답(마지막 A5 일반 단계 + 그 뒤 답 문장 단계, S8b). */
    TurnResult.Result storedGeneral(String caseId) {
        Answer a = generalPicks(caseId);
        if (a == null) {
            return null;
        }
        JsonNode t = jdbc.sql("""
                SELECT s.output_json FROM steps s WHERE s.case_id=? AND s.step=? AND s.name=? AND s.status IN ('OK','RETRY_OK')
                  AND s.seq > (SELECT MAX(p.seq) FROM steps p WHERE p.case_id=? AND p.step=? AND p.name=?
                               AND p.status IN ('OK','RETRY_OK'))
                ORDER BY s.seq DESC LIMIT 1""").params(caseId, GeneralAnswerStep.STEP, GeneralAnswerStep.NAME_TEXT, caseId,
                GeneralAnswerStep.STEP, GeneralAnswerStep.NAME).query(String.class).optional().map(json::readTree)
                .orElse(null);
        if (t != null && t.hasNonNull("answer")) {
            GeneralAnswerStep.GeneralText g = json.treeToValue(t, GeneralAnswerStep.GeneralText.class);
            a = a.withText(g.answer(), g.cites());
        }
        return TurnResult.general(a);
    }

    /** (afterStep, afterName)의 마지막 OK 단계 뒤에 쓴 (step, name) 마지막 OK 단계 출력 → 답. 1문장. */
    Answer answerAfter(String caseId, String afterStep, String afterName, String step, String name) {
        return jdbc.sql("""
                SELECT s.output_json FROM steps s WHERE s.case_id=? AND s.step=? AND s.name=? AND s.status IN ('OK','RETRY_OK')
                  AND s.seq > (SELECT MAX(p.seq) FROM steps p WHERE p.case_id=? AND p.step=? AND p.name=?
                               AND p.status IN ('OK','RETRY_OK'))
                ORDER BY s.seq DESC LIMIT 1""").params(caseId, step, name, caseId, afterStep, afterName)
                .query(String.class).optional().map(t -> json.readValue(t, Answer.class)).orElse(null);
    }

    // ───────── A4 꾸러미 · A5 답변 (S8) ─────────

    /** A4 결과(같은 요청이면 A5가 그대로 쓰고, 재시도면 스냅샷에서 다시 연다: 같은 사실·판이면 같은 결과). */
    record Opening(CaseJudge.Judgment judgment, PackageOpener.RefDate ref, PackageOpener.Opened opened) {}

    Opening opening(CaseState st) {
        CaseJudge.Judgment j = judges.judge(st, List.of());
        CaseState.CaseRow c = st.caseRow();
        PackageOpener.RefDate ref = PackageOpener.referenceDate(c.referenceDate(), c.referenceDateBasis(),
                clock.seoulToday());
        PackageOpener.Opened o = stages.packages().open(c.packageVersion(), issueRefs(j), ref.openDate());
        return new Opening(j, ref, o);
    }

    static List<PackageOpener.IssueRef> issueRefs(CaseJudge.Judgment j) {
        List<PackageOpener.IssueRef> out = new ArrayList<>();
        for (IssueDeriver.FlowIssues f : j.flows()) {
            for (IssueDeriver.Issue i : f.issues()) {
                out.add(new PackageOpener.IssueRef(f.flowNo(), i.issueName(), i.packageId(), i.conditionIds(),
                        i.interpOn(), i.opens().stream().map(x -> new PackageOpener.SubRef(x.packageId(),
                        x.conditionIds(), x.interpOn())).toList()));
            }
        }
        return out;
    }

    static List<SpecificAnswerStep.FlowIn> flowsIn(CaseJudge.Judgment j) {
        List<SpecificAnswerStep.FlowIn> out = new ArrayList<>();
        for (IssueDeriver.FlowIssues f : j.flows()) {
            Map<String, SpecificAnswerStep.SlotIn> slots = new LinkedHashMap<>();
            f.slots().forEach((k, v) -> slots.put(k, new SpecificAnswerStep.SlotIn(v.status(), v.values())));
            List<SpecificAnswerStep.IssueIn> issues = f.issues().stream().map(i -> new SpecificAnswerStep.IssueIn(
                    f.flowNo(), i.issueName(), i.packageId(), i.grade(), i.ruleIds(), i.conditionIds(),
                    i.allInterpOn())).toList();
            out.add(new SpecificAnswerStep.FlowIn(f.flowNo(), slots, issues));
        }
        return out;
    }

    /** A4 꾸러미 열기 단계(코드). output에는 키·해시·연결·시행 예정·크기·열기 µs(원문은 넣지 않음: 스냅샷에서 같은 키로 다시 나온다). */
    private void a4(Ctx ctx, CaseState st) {
        Opening op = opening(st);
        ctx.opening = op;
        writeA4(ctx, st, op, NextAction.A4_NAME, null);
    }

    /** A4 단계 쓰기(extra = 다시 열 때 더하는 칸: 까닭·횟수). */
    private void writeA4(Ctx ctx, CaseState st, Opening op, String name, Map<String, Object> extra) {
        PackageOpener.RefDate ref = op.ref();
        PackageOpener.Opened o = op.opened();
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("reference_date", ref.caseDate() == null ? null : String.valueOf(ref.caseDate()));
        in.put("reference_date_basis", ref.basis());
        in.put("open_date", String.valueOf(ref.openDate()));
        in.put("past", ref.past());
        in.put("from_today", ref.fromToday());
        in.put("package_version", o.packageVersion());
        in.put("issues", issueRefs(op.judgment()));
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> issues = new ArrayList<>();
        for (PackageOpener.OpenedIssue oi : o.issues()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("flow", oi.flow());
            m.put("issue", oi.issueName());
            m.put("package_id", oi.packageId());
            m.put("conditions", oi.conditionIds());
            m.put("items", oi.items().stream().map(PackageOpener.OpenedItem::itemId).toList());
            m.put("keys", oi.keys());
            m.put("linked", oi.linked());
            m.put("missing", oi.missing());
            issues.add(m);
        }
        out.put("issues", issues);
        Map<String, String> hashes = new LinkedHashMap<>();
        List<Map<String, Object>> upcoming = new ArrayList<>();
        for (PackageOpener.Source s : o.sources().values()) {
            hashes.put(s.key(), s.textHash());
            if (s.hasUpcoming()) {
                upcoming.add(Map.of("key", s.key(),
                        "from", s.upcoming().stream().map(u -> String.valueOf(u.effectiveFrom())).toList()));
            }
        }
        out.put("sources", hashes);
        out.put("pending_amendment", upcoming);
        out.put("units", o.sources().size());
        out.put("chars", o.chars());
        out.put("micros", o.micros());
        if (extra != null) {
            out.putAll(extra);
        }
        LocalDateTime at = clock.nowUtc();
        String caseId = ctx.caseId;
        int seq = lock.write(caseId, st.inputMark(), "A4", name, null, () -> store.addStep(
                NewStep.code(caseId, "A4", name, in, out, at, null, code.value())));
        ctx.steps.add(seq);
    }

    /** 마지막 A4 기록(꾸러미 열기 또는 다시 열기)의 키 → 해시. */
    Map<String, String> a4Record(String caseId) {
        Map<String, String> recorded = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT output_json FROM steps WHERE case_id=? AND step='A4' AND name IN (?, ?) AND status='OK'
                ORDER BY seq DESC LIMIT 1""").params(caseId, NextAction.A4_NAME, NextAction.A4_REOPEN_NAME)
                .query(String.class).optional().map(json::readTree).ifPresent(a4 -> {
                    if (a4.has("sources")) {
                        a4.get("sources").properties().forEach(e -> recorded.put(e.getKey(), e.getValue().asString()));
                    }
                });
        return recorded;
    }

    /** A7 데이터 검사: 지금 연 꾸러미의 키·해시가 A4 기록과 같은가. 다르면 그 키들(앞 5개). */
    static List<String> dataMismatch(Opening op, Map<String, String> recorded) {
        List<String> out = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>(recorded.keySet());
        keys.addAll(op.opened().sources().keySet());
        for (String k : keys) {
            PackageOpener.Source s = op.opened().sources().get(k);
            String h = recorded.get(k);
            if (s == null || h == null || !h.equals(s.textHash())) {
                out.add(k);
            }
        }
        return out;
    }

    static int gapQuestionsAsked(CaseState st) {
        return (int) st.steps().stream().filter(s -> "A2".equals(s.step()) && NextAction.A2_GAP_NAME.equals(s.name())
                && s.ok()).count();
    }

    /**
     * A5 구체 답변. 사실 부족 gap이 A2가 지금 물을 수 있는 후보(상한으로 멈춘 것 포함)의 사실을 말하면, 사안당 gapQuestions번까지 그 질문을
     * 낸다(QUESTION 턴, 단계 이름 A2_GAP_NAME). 없으면 그대로 결과로 간다.
     */
    private TurnResult a5(Ctx ctx, CaseState st, NextAction a) {
        Opening op = ctx.opening != null ? ctx.opening : opening(st);
        List<SpecificAnswerStep.FlowIn> flows = flowsIn(op.judgment());
        QuestionSelector.Selection sel = null;
        List<SpecificAnswerStep.Askable> askable = List.of();
        List<PreliminaryExit.Reason> external = lawReasons(ctx, st);
        if (gapQuestionsAsked(st) < stages.gapQuestions()) {
            sel = questions.next(st, facts.cloudItFlows(ctx.caseId), external);
            askable = sel.worth().stream().map(x -> new SpecificAnswerStep.Askable(x.flowNo(), x.factId())).toList();
        }
        final List<SpecificAnswerStep.Askable> ask0 = askable;
        // S9 A7 데이터 검사(메인 결정): 지금 연 꾸러미가 A4 기록과 다르면(그 사이 스냅샷이 바뀜) 입력 한 번에 1회 다시 열어 기록하고 A5로
        Map<String, String> recorded = a4Record(ctx.caseId);
        List<String> mismatch = dataMismatch(op, recorded);
        if (!mismatch.isEmpty()) {
            if (st.doneSinceInput("A4", NextAction.A4_REOPEN_NAME, null)) {
                throw llmFailed(ctx, a, "A7 데이터 검사: 다시 연 꾸러미도 기록과 다름 " + mismatch);
            }
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("reopen_count", 1);
            extra.put("reopen_reason", "A4 기록 해시 ≠ 지금 스냅샷: " + mismatch.subList(0, Math.min(5, mismatch.size())));
            writeA4(ctx, st, op, NextAction.A4_REOPEN_NAME, extra);
            recorded = a4Record(ctx.caseId);
        }
        SpecificAnswerStep.Result r = a5WithTools(ctx, st, op, flows, ask0, recorded);
        ctx.steps.addAll(r.steps());
        if (r.failed()) {
            throw llmFailed(ctx, a, r.error());
        }
        if (r.asked() == null || sel == null) {
            return null;
        }
        SpecificAnswerStep.Askable want = r.asked();
        QuestionSelector.Ask ask = sel.worth().stream()
                .filter(x -> java.util.Objects.equals(x.flowNo(), want.flow()) && x.factId().equals(want.fact()))
                .findFirst().orElseThrow();
        QuestionText.Text qt = questions.text(st, ask);
        String caseId = ctx.caseId;
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("from_step", r.steps().get(0));
        in.put("candidates", sel.openKeys());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ask", askMap(ask));
        out.put("why", "A5 사실 부족 gap이 이 사실을 말함(사안당 " + stages.gapQuestions() + "번까지): " + ask.why());
        LocalDateTime at = clock.nowUtc();
        int[] w = lock.write(caseId, st.inputMark(), "A2", NextAction.A2_GAP_NAME, null, () -> {
            int seq = store.addStep(NewStep.code(caseId, "A2", NextAction.A2_GAP_NAME, in, out, at, null, code.value()));
            int turn = store.addTurn(NewTurn.question(caseId, seq, ask.flowNo(), ask.factId(), ask.askNo(), qt.text(),
                    qt.choices(), at));
            return new int[] {seq, turn};
        });
        ctx.steps.add(w[0]);
        CaseState.CaseRow c = st.caseRow();
        return new TurnResult(caseId, c.state(), TurnResult.QUESTION, new TurnResult.Intake(c.intakeType(),
                c.outOfScopeKind()), new TurnResult.Question(w[1], ask.flowNo(), ask.factId(), ask.askNo(), qt.text(),
                qt.choices()), null, null, null);
    }

    // ───────── A6 보조 검색 (S10) ─────────

    /** A6 단계 이름(actor TOOL). 사안당 도구 호출 수는 이 두 이름의 OK 단계 수. */
    public static final String A6_SEARCH_NAME = "보조 검색(안내서)", A6_READ_NAME = "절 읽기", A6_STOP_NAME = "도구 멈춤";
    /** 도구 라운드를 하나 더 돌릴 턴 남은 시간 하한(A5 low 한 번 8~11초 + A7 요지 2~3초 + 여유). */
    static final java.time.Duration A6_MIN_LEFT = java.time.Duration.ofSeconds(25);

    static int a6Used(CaseState st) {
        return (int) st.steps().stream().filter(s -> "A6".equals(s.step()) && s.ok()
                && (A6_SEARCH_NAME.equals(s.name()) || A6_READ_NAME.equals(s.name()))).count();
    }

    /**
     * A5 구체 + A6 라운드(03 4.2 A6): 모델이 tool_requests를 내면(부를지·검색어 = LLM) 코드가 사안당 상한 안에서 돌리고(실행 = 코드),
     * 찾은 절의 글을 입력 tools.results에 붙여 A5를 다시 부른다. 마지막 호출의 답이 답이다(풀린 gap은 거기서 빠져 이관 사유도 빠진다).
     * 도구가 꺼졌거나 상한·턴 시간이 다 됐으면 지금 답 그대로. A6 단계와 A5 단계는 끝에 한 트랜잭션으로 쓴다.
     */
    private SpecificAnswerStep.Result a5WithTools(Ctx ctx, CaseState st, Opening op, List<SpecificAnswerStep.FlowIn> flows,
                                                  List<SpecificAnswerStep.Askable> ask0, Map<String, String> recorded) {
        GuideTools tools = stages.tools();
        boolean reduce = tools != null && tools.enabled() && tools.props().reduceInput();
        Rounds rs = a5Rounds(ctx, st, op, flows, recorded, reduce, false);
        return stages.specific().save(st, rs.last(), flows, op.opened(), op.ref(), ask0, rs.callIds(), rs.toolSteps());
    }

    /** A5(+A6) 라운드 결과(저장 전). calls = 라운드마다 마지막 호출(입력 글자 수·결과·보기). */
    record Rounds(SpecificAnswerStep.Call last, List<SpecificAnswerStep.Call> calls, List<Long> callIds,
                  List<NewStep> toolSteps) {}

    /** discarded = 견주기용(실제 호출 실행기): 호출을 버린 것으로 기록. 이 함수는 아무것도 저장하지 않는다. */
    Rounds a5Rounds(Ctx ctx, CaseState st, Opening op, List<SpecificAnswerStep.FlowIn> flows, Map<String, String> recorded,
                    boolean reduce, boolean discarded) {
        GuideTools tools = stages.tools();
        boolean on = tools != null && tools.enabled();
        int max = on ? tools.props().maxCallsPerCase() : 0;
        reduce = on && reduce;
        int used = on && !discarded ? a6Used(st) : 0;   // 견주기는 새 사안처럼 상한을 처음부터 센다
        List<SpecificAnswerStep.Call> calls = new ArrayList<>();
        List<SpecificAnswerStep.Interp> prior = new ArrayList<>();   // S10b: 도구를 부른 라운드의 해석 필요 gap
        CitationVerifier verifier = new CitationVerifier(knowledge.snapshot());
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, PackageOpener.Source> found = new LinkedHashMap<>();
        List<NewStep> toolSteps = new ArrayList<>();
        List<Long> callIds = new ArrayList<>();
        SpecificAnswerStep.Call c;
        int round = 0;
        while (true) {
            round++;
            SpecificAnswerStep.ToolView tv = new SpecificAnswerStep.ToolView(on, Math.max(0, max - used), reduce,
                    List.copyOf(results), Map.copyOf(found), List.copyOf(prior));
            CitationVerifier.Basis vb = new CitationVerifier.Basis(SpecificAnswerStep.allowedKeys(op.opened(), tv),
                    CitationVerifier.withA6(CitationVerifier.sources(op.opened()), found), recorded, ledgerRows(st),
                    st.caseRow().ledgerVersion(), op.opened().date(), true);
            c = llmStage(ctx, () -> stages.specific().call(st, flows, op.opened(), op.ref(), tv, ctx.deadline,
                    out -> verifier.verify(vb, CitationVerifier.issues(out)).reason(), discarded), x -> x.r().callIds());
            callIds.addAll(c.r().callIds());
            calls.add(c);
            if (!c.r().ok() || !on) {
                break;
            }
            List<AnswerOutput.ToolRequest> reqs = c.r().value().toolRequests();
            if (reqs.isEmpty()) {
                break;
            }
            String stop = used >= max ? "사안당 도구 상한 " + max + "회를 다 씀"
                    : java.time.Duration.between(java.time.Instant.now(), ctx.deadline).compareTo(A6_MIN_LEFT) < 0
                    ? "턴 남은 시간이 " + A6_MIN_LEFT.toSeconds() + "초 미만" : null;
            if (stop != null) {
                Map<String, Object> in = new LinkedHashMap<>();
                in.put("round", round);
                in.put("requests", reqs);
                toolSteps.add(toolStep(ctx.caseId, A6_STOP_NAME, in, Map.of("stopped", stop)));
                break;
            }
            boolean ran = false;
            for (AnswerOutput.ToolRequest q : reqs) {
                if (used >= max) {
                    break;
                }
                ran = true;
                used++;
                toolSteps.add(runTool(ctx.caseId, tools, op, q, round, used, max, results, found, c.r().callIds()));
            }
            if (ran) {
                for (AnswerOutput.GapOut g : c.r().value().gaps()) {
                    SpecificAnswerStep.Interp p = new SpecificAnswerStep.Interp(g.flow(), g.issue());
                    if (AnswerOutput.INTERPRETATION.equals(g.kind()) && g.issue() != null && !g.issue().isBlank()
                            && !prior.contains(p)) {
                        prior.add(p);
                    }
                }
            }
        }
        return new Rounds(c, List.copyOf(calls), List.copyOf(callIds), List.copyOf(toolSteps));
    }

    /**
     * 견주기(S10 실제 호출 실행기): 닫힌 사안의 지금 상태로 A5(+A6) 라운드를 저장 없이 다시 돌리고(호출은 버린 것으로 기록), 그 답으로
     * A7 검증·최종 출구까지 계산한다. reduce = 입력 줄이기.
     */
    public record Probe(List<Map<String, Object>> rounds, List<Map<String, Object>> tools, Answer answer,
                        CitationVerifier.Report report, ExitAssembler.FinalExit exit, List<Long> callIds) {}

    public Probe probeA5(String caseId, boolean reduce, java.time.Duration budget) {
        CaseState st = reader.load(caseId).orElseThrow();
        Ctx ctx = new Ctx(new TurnMeter(), java.time.Instant.now().plus(budget));
        ctx.caseId = caseId;
        Opening op = opening(st);
        List<SpecificAnswerStep.FlowIn> flows = flowsIn(op.judgment());
        Map<String, String> recorded = a4Record(caseId);
        Rounds rs = a5Rounds(ctx, st, op, flows, recorded, reduce, true);
        List<Map<String, Object>> rounds = new ArrayList<>();
        for (SpecificAnswerStep.Call c : rs.calls()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("input_chars", c.chars());
            m.put("status", c.r().status().name());
            m.put("call_ids", c.r().callIds());
            m.put("tool_requests", c.r().ok() ? c.r().value().toolRequests() : List.of());
            m.put("gaps", c.r().ok() ? c.r().value().gaps() : List.of());
            rounds.add(m);
        }
        List<Map<String, Object>> tools = new ArrayList<>();
        rs.toolSteps().forEach(t -> tools.add(Map.of("name", t.name(), "input", t.input(), "output", t.output())));
        Answer a = stages.specific().preview(st, rs.last(), flows, op.opened(), op.ref());
        if (a == null) {
            return new Probe(rounds, tools, null, null, null, rs.callIds());
        }
        KnowledgeSnapshot k = knowledge.snapshot();
        CitationVerifier.Report rep = new CitationVerifier(k).verify(citationBasis(ctx, st, a, op), CitationVerifier.issues(a));
        ExitAssembler ea = new ExitAssembler(new ExitPolicy(k.ruleSet().exitPolicy()));
        CaseJudge.Judgment j = judges.judge(st, externalReasons(ctx, st));
        QuestionSelector.Selection sel = questions.next(st, facts.cloudItFlows(caseId), lawReasons(ctx, st));
        Set<String> exitFacts = new LinkedHashSet<>();
        sel.worth().stream().filter(x -> x.impact() == QuestionSelector.Impact.EXIT).forEach(x ->
                exitFacts.add((x.flowNo() == null ? ExitAssembler.COMMON : String.valueOf(x.flowNo())) + ":" + x.factId()));
        return new Probe(rounds, tools, a, rep, ea.specific(j, a, rep, exitFacts), rs.callIds());
    }

    /** 도구 하나를 돌려 결과·찾은 절에 더하고 A6 단계(쓰기 전)를 돌려준다. 단계에는 키·해시·점수·ms만(글은 스냅샷에서 다시 나온다). */
    private NewStep runTool(String caseId, GuideTools tools, Opening op, AnswerOutput.ToolRequest q, int round, int n,
                            int max, List<Map<String, Object>> results, Map<String, PackageOpener.Source> found,
                            List<Long> fromCalls) {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("round", round);
        in.put("call_no", n);
        in.put("max", max);
        in.put("tool", q.tool());
        in.put("query", q.query());
        in.put("flow", q.flow());
        in.put("issue", q.issue());
        in.put("section_id", q.sectionId());
        in.put("why", q.why());
        in.put("from_calls", fromCalls);
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("tool", q.tool());
        List<Map<String, Object>> secs = new ArrayList<>();
        List<Map<String, Object>> logged = new ArrayList<>();
        String name;
        if (SpecificAnswerStep.TOOL_SEARCH.equals(q.tool())) {
            name = A6_SEARCH_NAME;
            Set<String> given = new LinkedHashSet<>(found.keySet());
            op.opened().sources().values().stream().filter(s -> PackageOpener.GUIDE_SECTION.equals(s.kind()))
                    .forEach(s -> given.add(s.key()));   // 꾸러미 절(글을 줬든 줄였든)은 키로 이미 보인다
            GuideTools.SearchResult r = tools.search(q.query(), q.issue(), tools.props().searchSections(), given);
            res.put("query", q.query());
            res.put("issue", q.issue());
            out.put("mode", r.mode());
            out.put("note", r.note());
            out.put("embed_ms", r.embedMs());
            out.put("search_ms", r.searchMs());
            for (GuideTools.Hit h : r.hits()) {
                GuideTools.Section s = tools.read(h.sectionId()).orElseThrow();
                secs.add(sectionMap(s, h.score()));
                found.put(s.sectionId(), a6Source(s));
                Map<String, Object> l = new LinkedHashMap<>();
                l.put("section_id", h.sectionId());
                l.put("score", h.score());
                l.put("vector_rank", h.vectorRank());
                l.put("text_rank", h.textRank());
                l.put("chunk_id", h.chunkId());
                l.put("hash", s.textHash());
                l.put("chars", s.chars());
                l.put("truncated", s.truncated());
                logged.add(l);
            }
        } else {
            name = A6_READ_NAME;
            res.put("section_id", q.sectionId());
            tools.read(q.sectionId()).ifPresent(s -> {
                secs.add(sectionMap(s, null));
                found.put(s.sectionId(), a6Source(s));
                logged.add(Map.of("section_id", s.sectionId(), "hash", s.textHash(), "chars", s.chars(),
                        "truncated", s.truncated()));
            });
        }
        res.put("sections", secs);
        results.add(res);
        out.put("sections", logged);
        return toolStep(caseId, name, in, out);
    }

    static Map<String, Object> sectionMap(GuideTools.Section s, Double score) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", s.sectionId());
        m.put("doc", s.doc());
        m.put("path", s.path());
        m.put("title", s.title());
        if (score != null) {
            m.put("score", score);
        }
        if (s.truncated()) {
            m.put("truncated", true);
        }
        m.put("text", s.text());
        return m;
    }

    /** A6 절 → 원문 단위(즉답 근거 아님: 출처가 A6이면 A7이 '해석 참고'로만 센다). */
    static PackageOpener.Source a6Source(GuideTools.Section s) {
        return new PackageOpener.Source(s.sectionId(), PackageOpener.GUIDE_SECTION, s.docId(), s.doc(), s.path(), s.title(),
                s.text(), s.textHash(), null, null, List.of(), s.usePolicy(), false);
    }

    private NewStep toolStep(String caseId, String name, Object in, Object out) {
        return new NewStep(caseId, "A6", "TOOL", name, in, out, clock.nowUtc(), null, null, null, null, null,
                code.value(), "OK", 1);
    }

    // ───────── A7 검증·출구 · A8 판정 기록 (S9) ─────────

    boolean isGeneral(CaseState st) {
        return IntakeJudge.GENERAL.equals(st.caseRow().intakeType());
    }

    /** 마지막 A5 답(구체: A5 단계 출력, 일반: G2 고르기 + 답 문장). */
    Answer storedAnswer(String caseId, boolean general) {
        if (general) {
            TurnResult.Result g = storedGeneral(caseId);
            return g == null ? null : g.answer();
        }
        JsonNode o = lastOutput(caseId, SpecificAnswerStep.STEP, SpecificAnswerStep.NAME);
        return o == null ? null : json.treeToValue(o, Answer.class);
    }

    /** 같은 이름의 마지막 OK 단계 번호. */
    Integer lastSeq(String caseId, String step, String name) {
        return jdbc.sql("""
                SELECT MAX(seq) FROM steps WHERE case_id=? AND step=? AND name=? AND status IN ('OK','RETRY_OK')""")
                .params(caseId, step, name).query(Integer.class).optional().orElse(null);
    }

    static Set<String> ledgerRows(CaseState st) {
        Set<String> out = new LinkedHashSet<>();
        st.currentFacts().forEach(f -> out.addAll(f.ledgerRows()));
        return out;
    }

    /** A7 인용 검증의 기준 재료(구체: 이번 꾸러미 키·A4 기록 해시·A3 후보 행, 일반: 고른 조 + 붙인 원문 단위). */
    CitationVerifier.Basis citationBasis(Ctx ctx, CaseState st, Answer a, Opening op) {
        String ledger = st.caseRow().ledgerVersion();
        java.time.LocalDate date = java.time.LocalDate.parse(a.openDate());
        if (Answer.GENERAL.equals(a.kind())) {
            Set<String> allowed = new LinkedHashSet<>();
            a.picks().forEach(p -> allowed.add(p.key()));
            a.sources().forEach(x -> allowed.add(x.key()));
            return new CitationVerifier.Basis(allowed, CitationVerifier.sources(a), null, Set.of(), ledger, date, true);
        }
        Map<String, String> recorded = a4Record(ctx.caseId);
        // S10: A5 답에 출처 A6으로 붙은 절도 인용해도 되는 키(해시는 스냅샷 절 해시와 다시 견준다)
        Set<String> allowed = new LinkedHashSet<>(op.opened().keys());
        if (a.sources() != null) {
            a.sources().stream().filter(Answer.Source::fromA6).forEach(x -> allowed.add(x.key()));
        }
        return new CitationVerifier.Basis(allowed, CitationVerifier.sources(a), recorded, ledgerRows(st),
                ledger, date, true);
    }

    /** 쟁점("흐름|쟁점") → 넘긴 꾸러미 원문 키. */
    static Map<String, List<String>> packageKeys(Opening op) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (op != null) {
            for (PackageOpener.OpenedIssue oi : op.opened().issues()) {
                out.put(oi.flow() + "|" + oi.issueName(), oi.keys());
            }
        }
        return out;
    }

    /** A7 판정 재료(같은 사실·판이면 같은 결과: A7과 A8이 따로 불려도 같다). */
    record Verdict(CaseJudge.Judgment judgment, Answer answer, CitationVerifier.Report report,
                   ExitAssembler.FinalExit exit, List<String> unasked, Opening opening) {}

    Verdict verdict(Ctx ctx, CaseState st) {
        KnowledgeSnapshot k = knowledge.snapshot();
        boolean general = isGeneral(st);
        Answer a = storedAnswer(ctx.caseId, general);
        if (a == null) {
            throw new IllegalStateException("A7: A5 답 없음 " + ctx.caseId);
        }
        ExitAssembler ea = new ExitAssembler(new ExitPolicy(k.ruleSet().exitPolicy()));
        CitationVerifier cv = new CitationVerifier(k);
        if (general) {
            CitationVerifier.Report rep = cv.verify(citationBasis(ctx, st, a, null), CitationVerifier.issues(a));
            return new Verdict(null, a, rep, ea.general(a, rep), List.of(), null);
        }
        Opening op = ctx.opening != null ? ctx.opening : opening(st);
        CitationVerifier.Report rep = cv.verify(citationBasis(ctx, st, a, op), CitationVerifier.issues(a));
        List<PreliminaryExit.Reason> external = externalReasons(ctx, st);
        CaseJudge.Judgment j = judges.judge(st, external);
        QuestionSelector.Selection sel = questions.next(st, facts.cloudItFlows(ctx.caseId), lawReasons(ctx, st));
        // S9b: A2가 지금도 사안 등급·출구를 바꿀 수 있다고 보는 사실(Impact.EXIT). OPEN 슬롯이 이것에 막혔을 때만 OPEN_UNKNOWN
        Set<String> exitFacts = new LinkedHashSet<>();
        sel.worth().stream().filter(x -> x.impact() == QuestionSelector.Impact.EXIT).forEach(x ->
                exitFacts.add((x.flowNo() == null ? ExitAssembler.COMMON : String.valueOf(x.flowNo())) + ":" + x.factId()));
        return new Verdict(j, a, rep, ea.specific(j, a, rep, exitFacts), sel.openKeys(), op);
    }

    /** A7(코드): 인용 검증 → 최종 출구 → 이관이면 검토 요청 묶음. 단계 output에 출구·검증·묶음(요지 전). */
    private void a7(Ctx ctx, CaseState st) {
        Verdict v = verdict(ctx, st);
        Map<String, Object> bundle = v.exit().transfer() ? bundle(st, v) : null;
        Map<String, Object> in = new LinkedHashMap<>();
        boolean general = isGeneral(st);
        in.put("answer_from_step", general ? lastSeq(ctx.caseId, GeneralAnswerStep.STEP, GeneralAnswerStep.NAME_TEXT)
                : lastSeq(ctx.caseId, SpecificAnswerStep.STEP, SpecificAnswerStep.NAME));
        in.put("a4_from_step", general ? null : lastSeq(ctx.caseId, "A4", NextAction.A4_NAME));
        Integer reopen = general ? null : lastSeq(ctx.caseId, "A4", NextAction.A4_REOPEN_NAME);
        in.put("a4_reopened_step", reopen);
        in.put("a4_reopen_count", st.doneSinceInput("A4", NextAction.A4_REOPEN_NAME, null) ? 1 : 0);
        in.put("open_date", v.answer().openDate());
        putLaw(in, ctx);
        in.put("rule", "사유가 하나라도 있으면 TRANSFER, 없으면 exit_policy[사안 등급](03 4.2 A7 4)");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exit", v.exit());
        out.put("verify", v.report().summary());
        out.put("cites", v.report().cites());
        out.put("bundle", bundle);
        LocalDateTime at = clock.nowUtc();
        String caseId = ctx.caseId;
        int seq = lock.write(caseId, st.inputMark(), "A7", NextAction.A7_NAME, null, () -> store.addStep(
                NewStep.code(caseId, "A7", NextAction.A7_NAME, in, out, at, null, code.value())));
        ctx.steps.add(seq);
    }

    Map<String, Object> bundle(CaseState st, Verdict v) {
        return new ReviewBundleBuilder(knowledge.snapshot()).build(new ReviewBundleBuilder.Input(st, v.judgment() == null
                ? new CaseJudge.Judgment(List.of(), null, new PreliminaryExit.Exit("TRANSFER", null, List.of()))
                : v.judgment(), v.exit(), v.answer(), v.report(), packageKeys(v.opening()), v.unasked(), v.answer().openDate()));
    }

    /**
     * A8: 이관이면 검토자 요지(A7_summary)를 먼저 쓰고(실패면 LLM_FAILED → API 3), 판정 테이블에 저장한 뒤 사안을 닫는다.
     * 출구·묶음은 A7 단계 output을 그대로 쓰고(감사: A7이 정한 것), 판정 행의 슬롯·쟁점은 같은 사실로 다시 낸 판정에서.
     */
    private void a8(Ctx ctx, CaseState st) {
        String caseId = ctx.caseId;
        JsonNode o7 = lastOutput(caseId, "A7", NextAction.A7_NAME);
        ExitAssembler.FinalExit ex = json.treeToValue(o7.get("exit"), ExitAssembler.FinalExit.class);
        Map<String, Object> bundle = o7.hasNonNull("bundle") ? json.convertValue(o7.get("bundle"), LinkedHashMap.class) : null;
        Integer summarySeq = null;
        if (ex.transfer()) {
            if (!st.doneSinceInput(ReviewSummaryStep.STEP, ReviewSummaryStep.NAME, null)) {
                int a7Seq = lastSeq(caseId, "A7", NextAction.A7_NAME);
                ReviewSummaryStep.Result r = llmStage(ctx, () -> verify.summary().run(st, summaryBundle(o7), a7Seq,
                        ctx.deadline), ReviewSummaryStep.Result::callIds);
                ctx.steps.addAll(r.steps());
                if (r.failed()) {
                    throw llmFailed(ctx, NextAction.A8, "A7_summary: " + r.error());
                }
            }
            JsonNode so = lastOutput(caseId, ReviewSummaryStep.STEP, ReviewSummaryStep.NAME);
            summarySeq = lastSeq(caseId, ReviewSummaryStep.STEP, ReviewSummaryStep.NAME);
            bundle.put("summary", so == null ? List.of() : json.convertValue(so.get("summary"), List.class));
        }
        CaseState now = reader.load(caseId).orElseThrow(() -> new CaseNotFound(caseId));
        Verdict v = verdict(ctx, now);
        Map<String, Integer> from = new LinkedHashMap<>();
        boolean general = isGeneral(now);
        if (general) {
            from.put("a5_general", lastSeq(caseId, GeneralAnswerStep.STEP, GeneralAnswerStep.NAME));
            from.put("a5_text", lastSeq(caseId, GeneralAnswerStep.STEP, GeneralAnswerStep.NAME_TEXT));
        } else {
            from.put("a3_exit", lastSeq(caseId, "A3", NextAction.A3_EXIT_NAME));
            from.put("a4", lastSeq(caseId, "A4", NextAction.A4_NAME));
            from.put("a5", lastSeq(caseId, SpecificAnswerStep.STEP, SpecificAnswerStep.NAME));
        }
        from.put("a7", lastSeq(caseId, "A7", NextAction.A7_NAME));
        if (summarySeq != null) {
            from.put("a7_summary", summarySeq);
        }
        from.values().removeIf(java.util.Objects::isNull);
        CaseJudge.Judgment j = v.judgment() != null ? v.judgment()
                : new CaseJudge.Judgment(List.of(), null, new PreliminaryExit.Exit(ex.exitType(), null, List.of()));
        java.time.LocalDate openDate = java.time.LocalDate.parse(v.answer().openDate());
        JudgmentRecorder.Written w = verify.recorder().write(knowledge.snapshot(), new JudgmentRecorder.Input(now, j, ex,
                v.answer(), v.report(), bundle, v.unasked(), openDate, from), clock.nowUtc());
        ctx.steps.add(w.stepSeq());
    }

    /** 요지 단계에 넘길 묶음(A7 단계 output의 bundle, 사유는 레코드로). */
    @SuppressWarnings("unchecked")
    Map<String, Object> summaryBundle(JsonNode o7) {
        Map<String, Object> b = json.convertValue(o7.get("bundle"), LinkedHashMap.class);
        List<ExitAssembler.Reason> rs = new ArrayList<>();
        for (JsonNode r : o7.get("exit").get("reasons")) {
            rs.add(json.treeToValue(r, ExitAssembler.Reason.class));
        }
        b.put("exit_reasons", rs);
        return b;
    }

    public Optional<JudgmentReader.Stored> storedJudgment(String caseId) {
        return verify.reader().latest(caseId);
    }

    /** 판정이 있으면 판정 테이블에서 지은 최종 결과, 없으면 예비 결과(옛 사안·A8 전). */
    TurnResult.Result finalOr(String caseId, java.util.function.Supplier<TurnResult.Result> fallback) {
        return verify.reader().latest(caseId).map(s -> finalResult(caseId, s)).orElseGet(fallback);
    }

    /** 최종 결과(S9): 판정 행(출구·등급·확정·슬롯·쟁점) + flags(구조 사유·흐름별 규칙·표시·unasked) + A8이 기댄 A5 답 + 묶음 요지. */
    TurnResult.Result finalResult(String caseId, JudgmentReader.Stored s) {
        JsonNode flags = s.flags();
        List<TurnResult.Reason> reasons = new ArrayList<>();
        if (flags != null && flags.has("reasons")) {
            for (JsonNode r : flags.get("reasons")) {
                reasons.add(new TurnResult.Reason(r.path("code").asString(), r.hasNonNull("flow") ? r.get("flow").asInt() : null,
                        r.path("detail").asString(null)));
            }
        }
        Map<Integer, JsonNode> flowFlags = new LinkedHashMap<>();
        if (flags != null && flags.has("flows")) {
            flags.get("flows").forEach(f -> flowFlags.put(f.get("flow").asInt(), f));
        }
        List<TurnResult.FlowResult> flows = new ArrayList<>();
        for (Map.Entry<Integer, JsonNode> e : flowFlags.entrySet()) {
            int n = e.getKey();
            JsonNode ff = e.getValue();
            Map<String, TurnResult.Slot> slots = new LinkedHashMap<>();
            for (JudgmentReader.SlotRow r : s.slots()) {
                if (r.flow() == n) {
                    slots.put(r.slot(), new TurnResult.Slot(r.status(), r.values(), r.basisFacts(), r.unknownFacts(),
                            strings(ff.path("open").path(r.slot())), strings(ff.path("slot_flags").path(r.slot()))));
                }
            }
            List<TurnResult.Issue> issues = new ArrayList<>();
            for (JudgmentReader.IssueRow i : s.issues()) {
                if (i.flow() != n) {
                    continue;
                }
                List<String> opens = new ArrayList<>();
                ff.path("sub_packages").forEach(sp -> {
                    if (i.issue().equals(sp.path("issue").asString())) {
                        opens.add(sp.path("package_id").asString());
                    }
                });
                issues.add(new TurnResult.Issue(i.issue(), i.packageId(), i.grade(), i.ruleIds(), i.conditions(),
                        i.interp(), opens));
            }
            flows.add(new TurnResult.FlowResult(n, ff.path("grade").asString(null), slots, issues,
                    strings(ff.path("fired_rules"))));
        }
        Answer a = null;
        Map<String, Integer> from = s.fromSteps();
        if (from.containsKey("a5")) {
            a = stepOutput(caseId, from.get("a5"), Answer.class);
        } else if (from.containsKey("a5_general")) {
            a = stepOutput(caseId, from.get("a5_general"), Answer.class);
            if (a != null && from.containsKey("a5_text")) {
                JsonNode t = stepOutput(caseId, from.get("a5_text"), JsonNode.class);
                if (t != null && t.hasNonNull("answer")) {
                    GeneralAnswerStep.GeneralText g = json.treeToValue(t, GeneralAnswerStep.GeneralText.class);
                    a = a.withText(g.answer(), g.cites());
                }
            }
        }
        List<String> summary = s.bundle() == null ? null : strings(s.bundle().path("summary"));
        boolean watch = flags != null && flags.path("omission_watch").asBoolean(false);
        return new TurnResult.Result(false, s.caseGrade(), s.exitType(), List.copyOf(reasons), watch, List.copyOf(flows),
                flags == null ? List.of() : strings(flags.path("unasked")), a, s.judgmentId(), s.conclusive(), summary,
                flags == null ? null : flags.get("verify"), flags == null ? null : flags.get("policy"),
                flags == null ? List.of() : strings(flags.path("unasked_no_effect")));
    }

    <T> T stepOutput(String caseId, int seq, Class<T> type) {
        return jdbc.sql("SELECT output_json FROM steps WHERE case_id=? AND seq=?").params(caseId, seq).query(String.class)
                .optional().map(t -> json.readValue(t, type)).orElse(null);
    }

    static List<String> strings(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n != null && n.isArray()) {
            n.forEach(x -> out.add(x.asString()));
        }
        return out;
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
            case END_RESULT -> new Peek(TurnResult.RESULT, null, null,
                    finalOr(st.caseRow().caseId(), () -> storedResult(st.caseRow().caseId())));
            case END_GENERAL -> new Peek(TurnResult.GENERAL, null, null,
                    finalOr(st.caseRow().caseId(), () -> storedGeneral(st.caseRow().caseId())));
            case END_TRANSFERRED -> new Peek(TurnResult.TRANSFERRED, null, null, null);
            default -> new Peek("PENDING", a.name(), null, null);
        };
    }
}
