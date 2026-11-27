package io.github.overdozya.privacyask.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class AppClockTest {
    @Test
    void utcTimeAndSeoulDate() {
        // UTC 11-27 16:30 = 서울 11-28 01:30
        AppClock c = new AppClock(Clock.fixed(Instant.parse("2026-11-27T16:30:00.123456Z"), ZoneOffset.UTC));
        assertThat(c.nowUtc()).isEqualTo(LocalDateTime.of(2026, 11, 27, 16, 30, 0, 123_000_000));
        assertThat(c.seoulToday()).isEqualTo(LocalDate.of(2026, 11, 28));
    }

    @Test
    void codeVersionFallsBackToUnknown() {
        assertThat(new CodeVersion(null).value()).isEqualTo("unknown");
        assertThat(new CodeVersion("${codeVersion}").value()).isEqualTo("unknown");
        assertThat(new CodeVersion("abc1234-dirty").value()).isEqualTo("abc1234-dirty");
    }
}
