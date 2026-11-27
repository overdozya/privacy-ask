package io.github.overdozya.privacyask.issues;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.overdozya.privacyask.knowledge.Documents;

/** 적용 법령 판정: 즉답 근거 법령이 아니거나 KB에 없으면 OUTSIDE_KB_LAW(06 3.3). 테스트 안에서 지은 문서 목록으로. */
class LawScopeJudgeTest {
    static Documents.Doc law(int id, String name, String policy) {
        return new Documents.Doc(id, "LAW", name, name, "법률", 1, null, null, policy, List.of(), null);
    }

    static final LawScopeJudge J = new LawScopeJudge(List.of(law(1, "개인정보 보호법", "ANSWER_BASIS"),
            law(2, "개인정보 보호법 시행령", "ANSWER_BASIS"), law(22, "신용정보법", "REFERENCE")));

    @Test
    void longestPrefixAndPolicy() {
        assertThat(J.match("개인정보보호법 시행령 제14조의2").docId()).isEqualTo(2);
        assertThat(J.match("개인정보 보호법 제15조").docId()).isEqualTo(1);
        assertThat(J.match("의료법")).isNull();
        assertThat(J.judge(List.of("개인정보 보호법", " "))).isEmpty();
        assertThat(J.judge(List.of("신용정보법 제32조", "의료법"))).extracting(PreliminaryExit.Reason::code)
                .containsExactly(PreliminaryExit.OUTSIDE_KB_LAW, PreliminaryExit.OUTSIDE_KB_LAW);
    }
}
