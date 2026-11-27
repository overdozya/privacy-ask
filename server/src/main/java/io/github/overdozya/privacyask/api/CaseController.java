package io.github.overdozya.privacyask.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.overdozya.privacyask.flow.CaseQueries;
import io.github.overdozya.privacyask.flow.FlowException;
import io.github.overdozya.privacyask.flow.TurnResult;
import io.github.overdozya.privacyask.flow.TurnService;

/**
 * API 1~6(06 5절). 경로 /api/cases. 요청자는 머리 X-Requester-Id(없으면 local, U3 (가)). 본인 사안만(남의 사안은 404).
 * JSON 이름은 snake_case(spring.jackson.property-naming-strategy).
 */
@RestController
@RequestMapping("/api/cases")
public class CaseController {
    public static final String REQUESTER = "X-Requester-Id";

    public record CreateRequest(String content, String serviceName) {}

    public record AnswerRequest(Integer questionTurn, List<String> choices, String detail, String text) {}

    private final TurnService turns;
    private final CaseQueries queries;

    public CaseController(TurnService turns, CaseQueries queries) {
        this.turns = turns;
        this.queries = queries;
    }

    static String requester(String header) {
        String r = header == null || header.isBlank() ? "local" : header.strip();
        if (r.length() > 20) {
            throw FlowException.of(400, FlowException.BAD_REQUEST, REQUESTER + "는 20자까지");
        }
        return r;
    }

    /** API 1. */
    @PostMapping
    public TurnResult create(@RequestHeader(value = REQUESTER, required = false) String who,
                             @RequestBody CreateRequest req) {
        return turns.create(requester(who), req.content(), req.serviceName());
    }

    /** API 2. */
    @PostMapping("/{id}/answers")
    public TurnResult answer(@RequestHeader(value = REQUESTER, required = false) String who, @PathVariable String id,
                             @RequestBody AnswerRequest req) {
        return turns.answer(requester(who), id, req.questionTurn(), req.choices(), req.detail(), req.text());
    }

    /** API 3. */
    @PostMapping("/{id}/retry")
    public TurnResult retry(@RequestHeader(value = REQUESTER, required = false) String who, @PathVariable String id) {
        return turns.retry(requester(who), id);
    }

    /** API 4. */
    @GetMapping("/{id}")
    public CaseQueries.CaseView get(@RequestHeader(value = REQUESTER, required = false) String who,
                                    @PathVariable String id) {
        return queries.view(requester(who), id);
    }

    /** API 8(S9): 검토 요청 묶음 — 이관으로 닫힌 사안의 판정 행 묶음(요지 포함). 이관 판정이 없으면 409 NO_REVIEW. */
    @GetMapping("/{id}/review")
    public CaseQueries.Review review(@RequestHeader(value = REQUESTER, required = false) String who,
                                     @PathVariable String id) {
        return queries.review(requester(who), id);
    }

    /** API 5. */
    @GetMapping("/{id}/trace")
    public CaseQueries.Trace trace(@RequestHeader(value = REQUESTER, required = false) String who,
                                   @PathVariable String id) {
        return queries.trace(requester(who), id);
    }

    /** API 6. */
    @GetMapping
    public CaseQueries.Page list(@RequestHeader(value = REQUESTER, required = false) String who,
                                 @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return queries.list(requester(who), page, size);
    }
}
