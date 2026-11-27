-- V8 (S8b, 메인 결정 2026-11-27): 호출 기록에 공급자 프롬프트 캐시 토큰(Gemini usageMetadata.cachedContentTokenCount).
-- A5 입력(꾸러미 원문 5~8만 토큰)이 재시도·gap 질문 뒤에 다시 갈 때 캐시가 맞는지 재려고. NULL = 공급자가 안 줌(캐시 안 맞음 포함).
ALTER TABLE llm_call_logs ADD COLUMN cached_tokens INT NULL AFTER thought_tokens;
