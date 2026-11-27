package io.github.overdozya.privacyask.llm;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/** llm_call_logs에 쓴다(V7). 자동 커밋: 사안 트랜잭션이 되돌려져도 시도 기록은 남는다. */
public class JdbcLlmCallLogWriter implements LlmCallLogWriter {
    private static final int TEXT_LIMIT = 1_000_000;

    private final JdbcClient jdbc;

    public JdbcLlmCallLogWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long write(CallLogEntry e) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO llm_call_logs (case_id, step_seq, prompt_id, prompt_version, provider, model, model_version,
                  temperature, attempt_no, outcome, http_status, finish_reason, latency_ms, prompt_tokens, output_tokens,
                  thought_tokens, input_sha256, response_text, error, discarded, at)
                VALUES (:caseId, :stepSeq, :promptId, :promptVersion, :provider, :model, :modelVersion,
                  :temperature, :attemptNo, :outcome, :httpStatus, :finishReason, :latencyMs, :promptTokens, :outputTokens,
                  :thoughtTokens, :inputSha256, :responseText, :error, :discarded, :at)
                """)
                .param("caseId", e.caseId())
                .param("stepSeq", e.stepSeq())
                .param("promptId", e.promptId())
                .param("promptVersion", e.promptVersion())
                .param("provider", e.provider())
                .param("model", e.model())
                .param("modelVersion", cut(e.modelVersion(), 100))
                .param("temperature", e.temperature())
                .param("attemptNo", e.attemptNo())
                .param("outcome", e.outcome().name())
                .param("httpStatus", e.httpStatus())
                .param("finishReason", cut(e.finishReason(), 40))
                .param("latencyMs", e.latencyMs() == null ? null : (int) Math.min(Integer.MAX_VALUE, e.latencyMs()))
                .param("promptTokens", e.promptTokens())
                .param("outputTokens", e.outputTokens())
                .param("thoughtTokens", e.thoughtTokens())
                .param("inputSha256", e.inputSha256())
                .param("responseText", cut(e.responseText(), TEXT_LIMIT))
                .param("error", cut(e.error(), 4000))
                .param("discarded", e.discarded())
                .param("at", e.at())
                .update(kh, "call_id");
        return kh.getKey().longValue();
    }

    private static String cut(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }
}
