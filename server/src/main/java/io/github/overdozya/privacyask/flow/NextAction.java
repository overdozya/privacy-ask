package io.github.overdozya.privacyask.flow;

import java.util.List;
import java.util.Optional;

import io.github.overdozya.privacyask.answer.GeneralAnswerStep;
import io.github.overdozya.privacyask.answer.SpecificAnswerStep;
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
    /** 예비 출구(쓰고 이어 감, S8). */
    A3_EXIT,
    /** 꾸러미 열기(코드, S8): 쟁점 → 꾸러미 원문, 기준일 판, 연결 한 단계. */
    A4,
    /** 구체 답변(LLM, S8). 사실 부족 gap이면 A2 후보가 있을 때 질문으로 돌아간다. */
    A5_SPECIFIC,
    /** 일반 질의 답변 G2(LLM, S8). */
    A5_GENERAL,
    /** 일반 질의 답 문장(LLM, S8b): 고른 조 원문만 보고 2~4문장. */
    A5_GENERAL_TEXT,
    /** 인용 검증·최종 출구·검토 요청 묶음(코드, S9). */
    A7,
    /** 판정 기록(코드, S9): 이관이면 먼저 검토자 요지(A7 LLM)를 쓰고, 판정 테이블에 저장한 뒤 사안을 닫는다(JUDGED·TRANSFERRED). */
    A8,
    END_GENERAL, END_TRANSFERRED, END_QUESTION, END_RESULT;

    public static final String A2_NAME = "다음 질문 고르기";
    public static final String A3_JUDGE_NAME = "슬롯 변환·쟁점 도출";
    public static final String A3_EXIT_NAME = "예비 출구";
    public static final String A4_NAME = "꾸러미 열기";
    /** S9: A7 데이터 검사(A4 기록 해시 ≠ 지금 스냅샷)가 실패해 다시 연 A4(입력 한 번에 1회, 메인 결정). */
    public static final String A4_REOPEN_NAME = "꾸러미 다시 열기";
    /** A5 사실 부족 gap을 A2 후보로 다시 묻는 질문 단계(A2_NAME과 다른 이름: 같은 입력 안에서 늦은 저장 검사가 겹치지 않게). */
    public static final String A2_GAP_NAME = "답변 뒤 질문";
    public static final String A7_NAME = "인용 검증·출구";
    public static final String A8_NAME = "판정 기록";

    public boolean ends() {
        return name().startsWith("END_");
    }

    public static NextAction decide(CaseState st, BaselineStep baseline) {
        CaseState.CaseRow c = st.caseRow();
        if (!"ASKING".equals(c.state())) {
            if (judged(st)) {
                return IntakeJudge.GENERAL.equals(c.intakeType()) ? END_GENERAL : END_RESULT;
            }
            return END_TRANSFERRED;
        }
        if (c.intakeType() == null) {
            return A0;
        }
        if (IntakeJudge.OUT_OF_SCOPE.equals(c.intakeType())) {
            return CLOSE_OUT_OF_SCOPE;
        }
        if (IntakeJudge.GENERAL.equals(c.intakeType())) {
            if (!st.doneSinceInput(GeneralAnswerStep.STEP, GeneralAnswerStep.NAME, null)) {
                return A5_GENERAL;
            }
            if (!st.doneSinceInput(GeneralAnswerStep.STEP, GeneralAnswerStep.NAME_TEXT, null)) {
                return A5_GENERAL_TEXT;
            }
            return verifyAndRecord(st, END_GENERAL);
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
        if (!st.doneSinceInput("A4", A4_NAME, null)) {
            return A4;
        }
        if (!st.doneSinceInput(SpecificAnswerStep.STEP, SpecificAnswerStep.NAME, null)) {
            return A5_SPECIFIC;
        }
        return verifyAndRecord(st, END_RESULT);
    }

    /** A5 뒤(S9): A7 → A8 → 끝. */
    static NextAction verifyAndRecord(CaseState st, NextAction end) {
        if (!st.doneSinceInput("A7", A7_NAME, null)) {
            return A7;
        }
        if (!st.doneSinceInput("A8", A8_NAME, null)) {
            return A8;
        }
        return end;
    }

    /** 판정 기록(A8)이 있는 사안(닫힌 뒤 API 3·4는 판정 테이블의 결과를 그대로 낸다). */
    static boolean judged(CaseState st) {
        return st.steps().stream().anyMatch(s -> "A8".equals(s.step()) && A8_NAME.equals(s.name()) && s.ok());
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
