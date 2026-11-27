package io.github.overdozya.privacyask.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 06 7절 S4 ③: CaseStore가 예시 사안을 거부 없이 넣고, 행 수가 build/가 만든 case.db와 같다. CaseReader로 다시 읽는다. */
class CaseStoreExampleTest {
    static RecordFixture fx;
    static ExampleCase ex;
    static String caseId;

    @BeforeAll
    static void load() throws Exception {
        assumeThat(ExampleCase.available()).as("예시 사안·적재 파일 또는 Docker 없음").isTrue();
        fx = new RecordFixture();
        ex = new ExampleCase();
        caseId = UUID.randomUUID().toString();
        ex.load(fx.store, caseId, true);
    }

    /** case.db(사안 1건)의 테이블별 행 수. sqlite3 명령이 없으면 건너뛴다. */
    static Map<String, Long> caseDbCounts() throws Exception {
        assumeThat(ExampleCase.CASE_DB.toFile()).exists();
        Map<String, Long> m = new LinkedHashMap<>();
        for (String t : RecordFixture.D9) {
            Process p;
            try {
                p = new ProcessBuilder("sqlite3", "-readonly", ExampleCase.CASE_DB.toString(), "SELECT COUNT(*) FROM " + t)
                        .redirectErrorStream(true).start();
            } catch (java.io.IOException e) {
                assumeThat(false).as("sqlite3 없음").isTrue();
                return m;
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            assertThat(p.waitFor()).as(t + ": " + out).isZero();
            m.put(t, Long.parseLong(out));
        }
        return m;
    }

    @Test
    void exampleCaseRowCountsEqualCaseDb() throws Exception {
        Map<String, Long> expected = caseDbCounts();
        Map<String, Long> got = fx.counts(caseId);
        assertThat(got).containsExactlyEntriesOf(expected);
        System.out.println("예시 사안 행 수 " + got.values().stream().mapToLong(Long::longValue).sum() + " = case.db " + got);
    }

    @Test
    void readerLoadsStateInOneGo() {
        CaseState s = fx.reader.load(caseId).orElseThrow();
        assertThat(s.caseRow().intakeType()).isEqualTo("SPECIFIC");
        assertThat(s.caseRow().packageVersion()).isEqualTo("v2");
        assertThat(s.flows()).hasSize(2);
        assertThat(s.turns()).hasSize(10);
        assertThat(s.facts()).hasSize(38);
        assertThat(s.steps()).hasSize(24);
        // 마지막 입력 = 턴 10(그 답이 받은 질문을 만든 A2 = 단계 15)
        assertThat(s.inputMark()).isEqualTo(new CaseState.InputMark(10, 15));
        // 사실 판: 흐름 1 F-C2는 문의에 없음(rev 1) 뒤 두 번 '잘 모르겠음'(rev 3), 흐름 2 F-C2는 '아니오'
        CaseState.Fact c2 = s.current(1, "F-C2").orElseThrow();
        assertThat(c2.rev()).isEqualTo(3);
        assertThat(c2.status()).isEqualTo("UNKNOWN");
        assertThat(s.current(2, "F-C2").orElseThrow().labels()).containsExactly("아니오");
        // 공통 사실은 흐름 번호로 찾아도 공통 판
        CaseState.Fact s1 = s.current(1, "F-S1").orElseThrow();
        assertThat(s1.flowNo()).isNull();
        assertThat(s1.values().get(0).detail()).isEqualTo("T 멤버십");
        // 원장 출처
        CaseState.Fact l1 = s.current(1, "L1").orElseThrow();
        assertThat(l1.status()).isEqualTo("MATCHED");
        assertThat(l1.ledgerRows()).containsExactly("첨부1-0040");
        assertThat(s.doneSinceInput("A3", "슬롯 변환·규칙 엔진(흐름별)", null)).isTrue();
        assertThat(fx.reader.trace(caseId)).hasSize(24)
                .first().satisfies(d -> assertThat(d.output().path("signals").size()).isEqualTo(2));
        assertThat(fx.reader.listByRequester("예시-요청자", 50, 0)).extracting(CaseState.CaseRow::caseId).contains(caseId);
        assertThat(fx.reader.load("없는-사안")).isEmpty();
    }
}
