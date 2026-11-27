package io.github.overdozya.privacyask.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.overdozya.privacyask.flow.CaseQueries;

/** API 7(06 5절): 지식 스냅샷 판·해시, 지금 모델·온도·프롬프트 판. 평가 기록에 그대로 쓴다. */
@RestController
public class KnowledgeController {
    private final CaseQueries queries;

    public KnowledgeController(CaseQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/api/knowledge")
    public CaseQueries.KnowledgeView knowledge() {
        return queries.knowledge();
    }
}
