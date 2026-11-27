package io.github.overdozya.privacyask.api;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import io.github.overdozya.privacyask.flow.FlowException;

/**
 * 오류 = ProblemDetail(RFC 9457) + code(06 5절). details는 그대로, INVALID_ANSWER면 question(같은 질문)도 넣는다.
 */
@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(FlowException.class)
    ResponseEntity<ProblemDetail> flow(FlowException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(e.status()), e.getMessage());
        p.setType(URI.create("urn:privacy-ask:" + e.code()));
        p.setTitle(e.code());
        p.setProperty("code", e.code());
        if (!e.details().isEmpty()) {
            p.setProperty("details", e.details());
        }
        if (e.question() != null) {
            p.setProperty("question", e.question());
        }
        return ResponseEntity.status(e.status()).body(p);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException e) {
        return flow(FlowException.of(400, FlowException.BAD_REQUEST, "요청 JSON을 읽지 못함"));
    }

    /** 지식 적재 전 기동(Knowledge.snapshot()이 "지식이 적재되지 않았다"로 던짐). 그 밖의 IllegalStateException은 500 그대로. */
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ProblemDetail> notLoaded(IllegalStateException e) {
        if (String.valueOf(e.getMessage()).startsWith("지식이 적재되지 않았다")) {
            return flow(FlowException.of(503, "KNOWLEDGE_NOT_LOADED", e.getMessage()));
        }
        return flow(FlowException.of(500, "INTERNAL", String.valueOf(e.getMessage())));
    }
}
