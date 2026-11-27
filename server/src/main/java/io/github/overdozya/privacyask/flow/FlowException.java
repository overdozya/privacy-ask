package io.github.overdozya.privacyask.flow;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * API 오류(06 5절). status = HTTP 상태, code = ProblemDetail의 code, details = 덧붙일 값(case_id, turn_no, C 코드 등).
 * question = INVALID_ANSWER일 때 같은 질문을 다시 보낸다.
 */
public class FlowException extends RuntimeException {
    public static final String CASE_NOT_FOUND = "CASE_NOT_FOUND", TURN_CONFLICT = "TURN_CONFLICT",
            NOT_ASKING = "NOT_ASKING", NOT_THE_QUESTION = "NOT_THE_QUESTION", INVALID_ANSWER = "INVALID_ANSWER",
            LLM_FAILED = "LLM_FAILED", TURN_TIMEOUT = "TURN_TIMEOUT", RECORD_REJECTED = "RECORD_REJECTED",
            BAD_REQUEST = "BAD_REQUEST", NO_REVIEW = "NO_REVIEW";

    private final int status;
    private final String code;
    private final Map<String, Object> details;
    private final TurnResult.Question question;

    public FlowException(int status, String code, String message, Map<String, Object> details,
                         TurnResult.Question question) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? Map.of() : new LinkedHashMap<>(details);
        this.question = question;
    }

    public static FlowException of(int status, String code, String message) {
        return new FlowException(status, code, message, null, null);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public TurnResult.Question question() {
        return question;
    }
}
