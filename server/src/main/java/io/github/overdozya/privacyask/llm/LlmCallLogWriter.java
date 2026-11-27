package io.github.overdozya.privacyask.llm;

/** 호출 기록 쓰기. 시도마다 바로 쓴다(사안 트랜잭션 밖, 실패한 시도도 남게). 돌려주는 값 = call_id. */
public interface LlmCallLogWriter {
    long write(CallLogEntry entry);
}
