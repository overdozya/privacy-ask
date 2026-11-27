package io.github.overdozya.privacyask.common;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * 시계 하나(06 2.2). DB 시각은 UTC(DATETIME(3)), 업무 날짜(기준일·오늘)는 서울 날짜.
 */
public final class AppClock {
    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final Clock clock;

    public AppClock(Clock clock) {
        this.clock = clock.withZone(ZoneOffset.UTC);
    }

    public static AppClock system() {
        return new AppClock(Clock.systemUTC());
    }

    public Instant now() {
        return clock.instant();
    }

    /** DB DATETIME(3)에 넣는 UTC 시각(밀리초까지). */
    public LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(now(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    }

    /** 법령 시행일과 견주는 오늘(서울 날짜). */
    public LocalDate seoulToday() {
        return LocalDate.ofInstant(now(), SEOUL);
    }
}
