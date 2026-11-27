package io.github.overdozya.privacyask.llm;

import com.networknt.schema.Schema;

import tools.jackson.databind.JsonNode;

/**
 * 프롬프트 하나(06 4.5). versionTag = "사람이 올린 버전@보낸 글 전체 sha256 앞 8자"(steps.prompt_version, C17).
 * system = 공통 파일 + 이 파일(머리 주석 뺌). schema = 보낼 JSON 스키마(단순판), strictSchema = 파일 그대로(검증용),
 * compiled = strictSchema를 컴파일한 것(우리 쪽 검증은 늘 엄격한 쪽).
 */
public record Prompt(
        String id,
        String version,
        String versionTag,
        String system,
        JsonNode schema,
        JsonNode strictSchema,
        Schema compiled,
        int maxOutputTokens,
        String thinking) {}
