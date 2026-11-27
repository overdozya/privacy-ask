package io.github.overdozya.privacyask.record;

/** 늦은 저장 거절(06 3.4): 처리한 입력이 더는 마지막 사용자 입력이 아니거나, 같은 단계가 이미 OK로 남음. API 409 TURN_CONFLICT. */
public class TurnConflict extends RuntimeException {
    public enum Reason { INPUT_CHANGED, ALREADY_DONE }

    private final Reason reason;

    public TurnConflict(Reason reason, String message) {
        super(reason + ": " + message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
