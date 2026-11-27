package io.github.overdozya.privacyask.record;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 늦은 저장 거절(06 3.4). 단계 결과를 쓰는 짧은 트랜잭션: cases 행을 FOR UPDATE로 잡고,
 * ① 이번에 처리한 입력(InputMark)이 여전히 마지막 사용자 입력이고 ② 그 입력 뒤에 같은 단계(step·name·flow_no)의 OK 행이 없을 때만
 * body를 돈다. 아니면 TurnConflict(409). LLM 호출은 이 트랜잭션 밖에서 한다.
 */
public class CaseLock {
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public CaseLock(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** 잠금 + 확인 + body를 한 트랜잭션으로. body 안의 CaseStore 호출은 이 트랜잭션에 합류한다(거부면 전부 되돌림). */
    public <T> T write(String caseId, CaseState.InputMark mark, String step, String name, Integer flowNo, Supplier<T> body) {
        return tx.execute(st -> {
            guard(caseId, mark, step, name, flowNo);
            return body.get();
        });
    }

    /** 사용자 입력 커밋용(답 턴): 잠금 + ①만. 답이 받는 질문이 마지막 질문인지는 부르는 쪽(NOT_THE_QUESTION)이 본다. */
    public <T> T writeInput(String caseId, CaseState.InputMark mark, Supplier<T> body) {
        return tx.execute(st -> {
            lockAndCheckInput(caseId, mark);
            return body.get();
        });
    }

    /** 이미 열린 트랜잭션 안에서 잠금 + 확인. */
    public void guard(String caseId, CaseState.InputMark mark, String step, String name, Integer flowNo) {
        lockAndCheckInput(caseId, mark);
        boolean done = !jdbc.sql("""
                SELECT 1 FROM steps WHERE case_id=? AND seq>? AND step=? AND name=? AND flow_no <=> ?
                  AND status IN ('OK','RETRY_OK') LIMIT 1""")
                .params(caseId, mark.boundarySeq(), step, name, flowNo).query().listOfRows().isEmpty();
        if (done) {
            throw new TurnConflict(TurnConflict.Reason.ALREADY_DONE,
                    caseId + " 입력 턴 " + mark.turnNo() + " 뒤에 " + step + " '" + name + "'가 이미 OK");
        }
    }

    private void lockAndCheckInput(String caseId, CaseState.InputMark mark) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("CaseLock은 트랜잭션 안에서만 쓴다");
        }
        Optional<String> row = jdbc.sql("SELECT case_id FROM cases WHERE case_id=? FOR UPDATE").param(caseId)
                .query(String.class).optional();
        if (row.isEmpty()) {
            throw new CaseNotFound(caseId);
        }
        CaseState.InputMark now = currentMark(caseId);
        if (!Objects.equals(now, mark)) {
            throw new TurnConflict(TurnConflict.Reason.INPUT_CHANGED,
                    caseId + " 처리한 입력 " + mark + " ≠ 지금 마지막 입력 " + now);
        }
    }

    /** DB에서 본 마지막 사용자 입력(CaseState.inputMark와 같은 규칙). */
    public CaseState.InputMark currentMark(String caseId) {
        return jdbc.sql("""
                SELECT turn_no, step_seq FROM turns WHERE case_id=? AND role='USER' ORDER BY turn_no DESC LIMIT 1""")
                .param(caseId).query((rs, i) -> new CaseState.InputMark(rs.getInt(1), rs.getInt(2))).optional()
                .orElse(CaseState.InputMark.INQUIRY);
    }
}
