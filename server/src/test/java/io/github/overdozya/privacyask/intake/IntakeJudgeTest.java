package io.github.overdozya.privacyask.intake;

import static io.github.overdozya.privacyask.intake.IntakeSignals.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.overdozya.privacyask.intake.IntakeJudge.Decision;
import io.github.overdozya.privacyask.intake.IntakeSignals.Signal;

/** 06 7절 S6 ①: IntakeJudge = 04 4.3 입구 규칙(결정 14). 신호는 손으로 쓴 것. */
class IntakeJudgeTest {

    static Decision judge(Signal... s) {
        return IntakeJudge.judge(new IntakeSignals(List.of(s)));
    }

    @Test
    void incidentIsOutOfScopeBreach() {
        Decision d = judge(Signal.of(INCIDENT, "고객 명단이 잘못 발송됐습니다"));
        assertThat(d.intakeType()).isEqualTo("OUT_OF_SCOPE");
        assertThat(d.outOfScopeKind()).isEqualTo("BREACH");
    }

    @Test
    void rightsRequestIsOutOfScopeDsr() {
        Decision d = judge(Signal.of(RIGHTS_REQUEST, "삭제 요청이 들어와 처리 중"));
        assertThat(d.intakeType()).isEqualTo("OUT_OF_SCOPE");
        assertThat(d.outOfScopeKind()).isEqualTo("DATA_SUBJECT_REQUEST");
    }

    @Test
    void outOfScopeWinsOverOwnCase() {
        // (다)·(라)는 자기 사안이어도 범위 밖. 둘 다면 사고가 먼저
        Decision d = judge(Signal.of(OWN_CASE, "저희 앱"), Signal.of(ACTION, "넘기려고"),
                Signal.of(RIGHTS_REQUEST, "열람 요청"), Signal.of(INCIDENT, "유출"));
        assertThat(d.intakeType()).isEqualTo("OUT_OF_SCOPE");
        assertThat(d.outOfScopeKind()).isEqualTo("BREACH");
    }

    @Test
    void ownCaseIsSpecific() {
        assertThat(judge(Signal.of(OWN_CASE, "저희 서비스")).intakeType()).isEqualTo("SPECIFIC");
        Decision d = judge(Signal.of(OWN_CASE, "저희 서비스"), Signal.of(ACTION, "맡기려고"));
        assertThat(d.intakeType()).isEqualTo("SPECIFIC");
        assertThat(d.outOfScopeKind()).isNull();
    }

    @Test
    void actionWithoutOwnCaseIsAmbiguousSoSpecific() {
        Decision d = judge(Signal.of(ACTION, "해외 서버에 보관"));
        assertThat(d.intakeType()).isEqualTo("SPECIFIC");
        assertThat(d.reason()).contains("애매하면 구체");
    }

    @Test
    void noSignalIsGeneral() {
        Decision d = judge();
        assertThat(d.intakeType()).isEqualTo("GENERAL");
        assertThat(d.reason()).isEqualTo("신호 없음");
    }

    @Test
    void lawAreaOnlyIsGeneralAndOnlyShown() {
        Decision d = judge(new Signal(LAW_AREA, "위치정보법", "위치정보법"), new Signal(LAW_AREA, "신용정보", null));
        assertThat(d.intakeType()).isEqualTo("GENERAL");
        assertThat(d.lawNames()).containsExactly("위치정보법", "신용정보");
        assertThat(d.signalCounts()).containsEntry(LAW_AREA, 2).containsEntry(OWN_CASE, 0);
    }

    @Test
    void lawAreaDoesNotChangeSpecific() {
        Decision d = judge(Signal.of(OWN_CASE, "우리 회사"), new Signal(LAW_AREA, "근로", "근로"));
        assertThat(d.intakeType()).isEqualTo("SPECIFIC");
        assertThat(d.lawNames()).containsExactly("근로");
    }

    @Test
    void checkRejectsQuoteNotInContent() {
        String content = "저희 앱 회원 번호를 제휴사에 넘기려고 합니다.";
        assertThat(IntakeStep.check(content, new IntakeSignals(List.of(Signal.of(OWN_CASE, "저희 앱"))))).isNull();
        assertThat(IntakeStep.check(content, new IntakeSignals(List.of(Signal.of(ACTION, "제휴사에 넘기려고 합니다"),
                Signal.of(OWN_CASE, "저희 앱 … 넘기려고"))))).isNull();
        // 고쳐 쓴 문구(띄어쓰기 다름)와 지어낸 문구는 거절
        assertThat(IntakeStep.check(content, new IntakeSignals(List.of(Signal.of(ACTION, "제휴사에넘기려고")))))
                .contains("문의 원문에 없음");
        assertThat(IntakeStep.check(content, new IntakeSignals(List.of(Signal.of(OWN_CASE, "우리 회사")))))
                .contains("'우리 회사'");
    }
}
