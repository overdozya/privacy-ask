package io.github.overdozya.privacyask.llm;

import java.time.Duration;

/**
 * 전송 실패(06 4.3). retryable: 429·5xx·시간 초과·연결 오류. 400·401·403 등은 설정 오류라 다시 하지 않는다.
 * 메시지·body에 키가 들어가지 않게 공급자 쪽에서 가린다.
 */
public class ProviderException extends RuntimeException {
    public enum Kind { TIMEOUT, HTTP, CONNECT, CONFIG }

    private final Kind kind;
    private final Integer httpStatus;
    private final boolean retryable;
    private final Duration retryAfter;
    private final long latencyMs;
    private final String body;

    public ProviderException(Kind kind, Integer httpStatus, boolean retryable, Duration retryAfter,
                             long latencyMs, String body, String message) {
        super(message);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
        this.retryAfter = retryAfter;
        this.latencyMs = latencyMs;
        this.body = body;
    }

    public static ProviderException http(int status, Duration retryAfter, long latencyMs, String body) {
        boolean retry = status == 429 || status >= 500;
        return new ProviderException(Kind.HTTP, status, retry, retryAfter, latencyMs, body, "HTTP " + status);
    }

    public static ProviderException timeout(long latencyMs) {
        return new ProviderException(Kind.TIMEOUT, null, true, null, latencyMs, null, "시간 초과");
    }

    public static ProviderException connect(long latencyMs, String message) {
        return new ProviderException(Kind.CONNECT, null, true, null, latencyMs, null, "연결 오류: " + message);
    }

    public static ProviderException config(String message) {
        return new ProviderException(Kind.CONFIG, null, false, null, 0, null, message);
    }

    public Kind kind() { return kind; }
    public Integer httpStatus() { return httpStatus; }
    public boolean retryable() { return retryable; }
    public Duration retryAfter() { return retryAfter; }
    public long latencyMs() { return latencyMs; }
    public String body() { return body; }
}
