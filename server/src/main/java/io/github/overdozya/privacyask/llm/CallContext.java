package io.github.overdozya.privacyask.llm;

import java.time.Instant;

/**
 * 호출이 어느 사안·단계의 것인지와 턴 마감(06 4.3·6.1). caseId·stepSeq가 null이면 기동 확인 호출.
 * discarded = 버린 추측 호출(06 6.3)로 기록.
 */
public record CallContext(String caseId, Integer stepSeq, Instant deadline, boolean discarded) {

    public static CallContext standalone(Instant deadline) {
        return new CallContext(null, null, deadline, false);
    }

    public static CallContext forStep(String caseId, int stepSeq, Instant deadline) {
        return new CallContext(caseId, stepSeq, deadline, false);
    }

    public CallContext asDiscarded() {
        return new CallContext(caseId, stepSeq, deadline, true);
    }
}
