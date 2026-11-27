package io.github.overdozya.privacyask.llm;

import java.util.List;
import java.util.stream.Collectors;

import com.networknt.schema.Error;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** LLM 글 → JSON → 스키마 검사(06 4.3). 실패 이유는 한 줄(재시도 때 user 글 끝에 붙인다). */
public final class OutputValidator {
    private final JsonMapper json;

    public OutputValidator(JsonMapper json) {
        this.json = json;
    }

    public record Checked(JsonNode node, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    public Checked check(Prompt prompt, String text) {
        if (text == null || text.isBlank()) {
            return new Checked(null, "빈 출력");
        }
        JsonNode node;
        try {
            node = json.readTree(text.strip());
        } catch (JacksonException e) {
            return new Checked(null, "JSON이 아님: " + e.getOriginalMessage());
        }
        if (node == null || !node.isObject()) {
            return new Checked(null, "JSON 객체 하나가 아님");
        }
        List<Error> errors = prompt.compiled().validate(node);
        if (!errors.isEmpty()) {
            String msg = errors.stream().limit(5)
                    .map(e -> e.getInstanceLocation() + " " + e.getMessage())
                    .collect(Collectors.joining("; "));
            return new Checked(node, "스키마 위반: " + msg);
        }
        return new Checked(node, null);
    }
}
