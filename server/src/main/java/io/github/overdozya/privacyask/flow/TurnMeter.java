package io.github.overdozya.privacyask.flow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 한 턴의 숫자(06 6.4 turn_logs): 전체 ms, LLM ms, LLM 시도 수, 직렬 LLM 깊이, DB 문장 수, NextAction 경로, A2 계산.
 * DB 문장 수는 CountingDataSource가 이 턴이 연 연결에서 문장을 준비할 때마다 센다(JDBC prepareStatement·createStatement·prepareCall
 * 1회 = 1문장, 묶음 INSERT도 1). 턴을 처리하는 스레드에 ThreadLocal로 걸고, A0 ‖ A1처럼 다른 스레드로 넘길 때는 wrap으로 옮긴다.
 */
public final class TurnMeter {
    private static final ThreadLocal<TurnMeter> CURRENT = new ThreadLocal<>();

    private final long startNanos = System.nanoTime();
    private final AtomicInteger dbStatements = new AtomicInteger();
    private final AtomicInteger llmCalls = new AtomicInteger();
    private final AtomicLong llmNanos = new AtomicLong();
    private final AtomicInteger llmDepth = new AtomicInteger();
    private final List<String> path = new ArrayList<>();
    private String open = "INPUT";          // 지금 단계(요청 받기·사용자 입력 커밋 = INPUT)
    private int openAt = 0;                 // 그 단계가 시작될 때의 DB 문장 수
    private final Map<String, Object> a2 = new LinkedHashMap<>();

    public static TurnMeter current() {
        return CURRENT.get();
    }

    /** 이 스레드에 건다. 돌려준 것을 close하면 풀린다. */
    public Scope bind() {
        TurnMeter old = CURRENT.get();
        CURRENT.set(this);
        return () -> {
            if (old == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(old);
            }
        };
    }

    /** 다른 스레드에서 돌 일에 이 측정을 옮긴다. */
    public <T> Callable<T> wrap(Callable<T> c) {
        return () -> {
            try (Scope s = bind()) {
                return c.call();
            }
        };
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    void countStatement() {
        dbStatements.incrementAndGet();
    }

    /** LLM 단계 하나(병렬이면 묶음 하나): 벽시계 ms를 더하고 직렬 깊이 1. calls = 그 단계의 시도 수(callIds). */
    void llmStage(long nanos, int calls) {
        if (calls <= 0) {
            return;
        }
        llmNanos.addAndGet(nanos);
        llmCalls.addAndGet(calls);
        llmDepth.incrementAndGet();
    }

    /** 다음 할 일 하나를 시작한다. 앞 일은 "이름(그동안 DB 문장 수)"로 닫는다(상태 읽기 5문장은 그 일에 들어감). */
    synchronized void step(String action) {
        closeOpen();
        open = action;
        openAt = dbStatements.get();
    }

    private void closeOpen() {
        if (open != null) {
            path.add(open + "(" + (dbStatements.get() - openAt) + ")");
            open = null;
        }
    }

    synchronized void a2(int considered, int evaluations, long micros) {
        a2.merge("runs", 1, (x, y) -> (Integer) x + (Integer) y);
        a2.merge("considered", considered, (x, y) -> (Integer) x + (Integer) y);
        a2.merge("evaluations", evaluations, (x, y) -> (Integer) x + (Integer) y);
        a2.merge("micros", micros, (x, y) -> (Long) x + (Long) y);
    }

    public int totalMs() {
        return (int) ((System.nanoTime() - startNanos) / 1_000_000);
    }

    public int llmMs() {
        return (int) (llmNanos.get() / 1_000_000);
    }

    public int llmCalls() {
        return llmCalls.get();
    }

    public int llmDepth() {
        return llmDepth.get();
    }

    public int dbStatements() {
        return dbStatements.get();
    }

    /** NextAction 차례와 일마다 DB 문장 수. 읽으면 열린 일을 닫는다. */
    public synchronized List<String> path() {
        closeOpen();
        return List.copyOf(path);
    }

    public synchronized Map<String, Object> a2() {
        return Map.copyOf(a2);
    }
}
