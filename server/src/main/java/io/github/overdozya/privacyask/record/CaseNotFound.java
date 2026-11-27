package io.github.overdozya.privacyask.record;

/** API 404 CASE_NOT_FOUND. */
public class CaseNotFound extends RuntimeException {
    public CaseNotFound(String caseId) {
        super("사안 없음: " + caseId);
    }
}
