package io.github.overdozya.privacyask.flow;

import java.util.List;
import java.util.Optional;

import io.github.overdozya.privacyask.baseline.BaselineStep;
import io.github.overdozya.privacyask.baseline.LedgerCandidates;
import io.github.overdozya.privacyask.facts.AnswerApplier;
import io.github.overdozya.privacyask.facts.FactExtractStep;
import io.github.overdozya.privacyask.intake.IntakeJudge;
import io.github.overdozya.privacyask.record.CaseState;
import io.github.overdozya.privacyask.watch.OmissionWatchStep;

/**
 * 다음 할 일(06 3.4): DB에서 읽은 사안 상태만 보고 정한다. 그래서 첫 요청·답·재시도·이어하기가 모두 같은 함수다.
 * END_*는 이번 요청을 끝내는 것(응답 종류), 나머지는 단계 하나를 돌고 상태를 다시 읽는다.
 */
public enum NextAction {
    /** A0 입구(첫 요청이면 A1을 같이 띄움, 06 6.3). */
    A0,
    /** 범위 밖 → state TRANSFERRED. */
    CLOSE_OUT_OF_SCOPE,
    /** 문의 글 사실 추출(LLM). */
    A1_INQUIRY,
    /** 보기 답 → 사실(코드, AnswerApplier). */
    A1_CHOICE,
    /** 자유 답 사실 추출(LLM). */
    A1_FREE,
    /** 원장 대조(후보 → LLM 인용 → 판정). */
    A3_BASELINE,
    /** 다음 질문 고르기. 질문이 있으면 QUESTION 턴, 없으면 A3 쟁점 도출까지 쓴다. */
    A2,
    /** 누락 감시(LLM). */
    A3_WATCH,
    /** 예비 출구. */
    A3_EXIT,
    END_GENERAL, END_TRANSFERRED, END_QUESTION, END_RESULT;

    public static final String A2_NAME = "다음 질문 고르기";
    public static final String A3_JUDGE_NAME = "슬롯 변환·쟁점 도출";
    public static final String A3_EXIT_NAME = "예비 출구";

    public boolean ends() {
        return name().startsWith("END_");
    }

    public static NextAction decide(CaseState st, BaselineStep baseline) {
        CaseState.CaseRow c = st.caseRow();
        if (!"ASKING".equals(c.state())) {
            return END_TRANSFERRED;
        }
        if (c.intakeType() == null) {
            return A0;
        }
        if (IntakeJudge.OUT_OF_SCOPE.equals(c.intakeType())) {
            return CLOSE_OUT_OF_SCOPE;
        }
        if (IntakeJudge.GENERAL.equals(c.intakeType())) {
            return END_GENERAL;
        }
        Optional<CaseState.Turn> user = st.lastUserTurn();
        if (user.isEmpty()) {
            if (!st.doneSinceInput(FactExtractStep.STEP, FactExtractStep.NAME_INQUIRY, null)) {
                return A1_INQUIRY;
            }
        } else {
            CaseState.Turn t = user.get();
            AnswerText.Parsed p = AnswerText.parse(t.choices(), t.text());
            if (!t.choices().isEmpty() && !st.doneSinceInput(AnswerApplier.STEP, AnswerApplier.NAME, null)) {
                return A1_CHOICE;
            }
            if ((t.choices().isEmpty() || p.hasFreeText())
                    && !st.doneSinceInput(FactExtractStep.STEP, FactExtractStep.NAME_ANSWER, null)) {
                return A1_FREE;
            }
        }
        if (needBaseline(st, baseline)) {
            return A3_BASELINE;
        }
        if (!st.doneSinceInput("A2", A2_NAME, null)) {
            return A2;
        }
        int boundary = st.inputMark().boundarySeq();
        Optional<CaseState.Turn> last = st.lastTurn();
        if (last.isPresent() && last.get().isQuestion() && last.get().stepSeq() > boundary) {
            return END_QUESTION;
        }
        if (!st.doneSinceInput(OmissionWatchStep.STEP, OmissionWatchStep.NAME, null)) {
            return A3_WATCH;
        }
        if (!st.doneSinceInput("A3", A3_EXIT_NAME, null)) {
            return A3_EXIT;
        }
        return END_RESULT;
    }

    /**
     * 원장 대조가 필요한가: 이번 입력 뒤에 판정이 없고, 원장 사실이 없는 흐름이 있으며, (한 번도 대조하지 않았거나 그 흐름 중 서비스명이
     * 생긴 흐름이 있음). 서비스명이 없는 흐름만 남았으면 매 답마다 다시 돌지 않는다(A2가 F-S1을 먼저 묻는다).
     */
    static boolean needBaseline(CaseState st, BaselineStep baseline) {
        if (st.doneSinceInput(BaselineStep.STEP, BaselineStep.NAME_VERDICT, null)) {
            return false;
        }
        List<Integer> pending = baseline.pendingFlows(st);
        if (pending.isEmpty()) {
            return false;
        }
        boolean ever = st.steps().stream().anyMatch(s -> BaselineStep.STEP.equals(s.step())
                && BaselineStep.NAME_VERDICT.equals(s.name()) && s.ok());
        if (!ever) {
            return true;
        }
        return pending.stream().map(n -> st.flow(n).orElseThrow())
                .anyMatch(f -> LedgerCandidates.serviceFor(st, f).isPresent());
    }
}
