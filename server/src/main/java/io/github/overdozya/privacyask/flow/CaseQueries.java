package io.github.overdozya.privacyask.flow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.overdozya.privacyask.knowledge.Knowledge;
import io.github.overdozya.privacyask.knowledge.KnowledgeSnapshot;
import io.github.overdozya.privacyask.llm.LlmProperties;
import io.github.overdozya.privacyask.llm.PromptCatalog;
import io.github.overdozya.privacyask.record.CaseInput;
import io.github.overdozya.privacyask.record.CaseReader;
import io.github.overdozya.privacyask.record.CaseState;

/**
 * 읽기 API(06 5절 4~7). 쓰지 않는다. 본인 사안만(남의 사안도 404).
 */
public class CaseQueries {
    private final CaseReader reader;
    private final JdbcClient jdbc;
    private final TurnService turns;
    private final Knowledge knowledge;
    private final PromptCatalog prompts;
    private final LlmProperties llm;

    public CaseQueries(CaseReader reader, JdbcClient jdbc, TurnService turns, Knowledge knowledge, PromptCatalog prompts,
                       LlmProperties llm) {
        this.reader = reader;
        this.jdbc = jdbc;
        this.turns = turns;
        this.knowledge = knowledge;
        this.prompts = prompts;
        this.llm = llm;
    }

    // ───────── API 4 ─────────

    public record CaseView(CaseInfo caseInfo, List<CaseState.Flow> flows, List<FactView> facts, List<CaseState.Turn> turns,
                           TurnService.Peek last) {}

    public record CaseInfo(String caseId, String content, String serviceName, String intakeType, String outOfScopeKind,
                           String state, String ruleSetVersion, String packageVersion, String ledgerVersion,
                           LocalDate referenceDate, String referenceDateBasis, LocalDateTime createdAt,
                           LocalDateTime updatedAt, LocalDateTime closedAt) {
        static CaseInfo of(CaseState.CaseRow c) {
            return new CaseInfo(c.caseId(), c.content(), c.serviceName(), c.intakeType(), c.outOfScopeKind(), c.state(),
                    c.ruleSetVersion(), c.packageVersion(), c.ledgerVersion(), c.referenceDate(),
                    c.referenceDateBasis(), c.createdAt(), c.updatedAt(), c.closedAt());
        }
    }

    /** 지금 사실 판 하나(값·상태·근거 문구). */
    public record FactView(Integer flow, String factId, int rev, String status, List<CaseInput.Value> values,
                           String sourceKind, Integer sourceTurn, int sourceStep, String basisQuote, List<String> ledgerRows,
                           String note) {
        static FactView of(CaseState.Fact f) {
            return new FactView(f.flowNo(), f.factId(), f.rev(), f.status(), f.values(), f.sourceKind(), f.sourceTurn(),
                    f.sourceStep(), f.basisQuote(), f.ledgerRows(), f.note());
        }
    }

    public CaseView view(String requester, String caseId) {
        CaseState st = turns.loadOwned(requester, caseId);
        return new CaseView(CaseInfo.of(st.caseRow()), st.flows(), st.currentFacts().stream().map(FactView::of).toList(),
                st.turns(), turns.peek(st));
    }

    // ───────── API 5 ─────────

    public record Call(long callId, Integer stepSeq, String promptId, String promptVersion, String model,
                       BigDecimal temperature, int attemptNo, String outcome, String finishReason, Integer latencyMs,
                       Integer promptTokens, Integer outputTokens, Integer thoughtTokens, boolean discarded,
                       LocalDateTime at) {}

    public record Trace(String caseId, List<CaseReader.StepDetail> steps, List<Call> calls) {}

    public Trace trace(String requester, String caseId) {
        turns.loadOwned(requester, caseId);
        List<Call> calls = jdbc.sql("""
                SELECT call_id, step_seq, prompt_id, prompt_version, model, temperature, attempt_no, outcome, finish_reason,
                       latency_ms, prompt_tokens, output_tokens, thought_tokens, discarded, at
                FROM llm_call_logs WHERE case_id=? ORDER BY call_id""").param(caseId)
                .query((rs, i) -> new Call(rs.getLong("call_id"), (Integer) rs.getObject("step_seq"),
                        rs.getString("prompt_id"), rs.getString("prompt_version"), rs.getString("model"),
                        rs.getBigDecimal("temperature"), rs.getInt("attempt_no"), rs.getString("outcome"),
                        rs.getString("finish_reason"), (Integer) rs.getObject("latency_ms"),
                        (Integer) rs.getObject("prompt_tokens"), (Integer) rs.getObject("output_tokens"),
                        (Integer) rs.getObject("thought_tokens"), rs.getBoolean("discarded"),
                        rs.getObject("at", LocalDateTime.class)))
                .list();
        return new Trace(caseId, reader.trace(caseId), calls);
    }

    // ───────── API 6 ─────────

    public record CaseRowView(String caseId, String content, String serviceName, String intakeType, String state,
                              LocalDateTime createdAt, LocalDateTime updatedAt) {}

    public record Page(int page, int size, List<CaseRowView> cases) {}

    public Page list(String requester, int page, int size) {
        int p = Math.max(0, page), s = Math.min(100, Math.max(1, size));
        List<CaseRowView> rows = jdbc.sql("""
                SELECT case_id, content, service_name, intake_type, state, created_at, updated_at FROM cases
                WHERE requester_id=? ORDER BY created_at DESC, case_id LIMIT ? OFFSET ?""")
                .params(requester, s, p * s)
                .query((rs, i) -> new CaseRowView(rs.getString("case_id"), cut(rs.getString("content"), 80),
                        rs.getString("service_name"), rs.getString("intake_type"), rs.getString("state"),
                        rs.getObject("created_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class)))
                .list();
        return new Page(p, s, rows);
    }

    private static String cut(String t, int n) {
        return t == null || t.length() <= n ? t : t.substring(0, n) + "…";
    }

    // ───────── API 7 ─────────

    public record PromptVersion(String promptId, String version) {}

    public record KnowledgeView(KnowledgeSnapshot.Versions versions, String provider, String model, BigDecimal temperature,
                                List<PromptVersion> prompts, Map<String, Object> stats) {}

    public KnowledgeView knowledge() {
        KnowledgeSnapshot k = knowledge.snapshot();
        List<PromptVersion> ps = prompts.all().stream().map(p -> new PromptVersion(p.id(), p.versionTag()))
                .sorted(java.util.Comparator.comparing(PromptVersion::promptId)).toList();
        return new KnowledgeView(k.versions(), llm.provider(), llm.model(), llm.temperature(), ps,
                Map.of("total_rows", k.stats().totalRows()));
    }
}
