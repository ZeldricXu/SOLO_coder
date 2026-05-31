package com.taskplatform.common.util;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class PerformanceUtils {

    private static final ThreadLocal<Long> START_TIME = new ThreadLocal<>();

    private PerformanceUtils() {}

    public static void startTimer() {
        START_TIME.set(System.nanoTime());
    }

    public static long stopTimer() {
        Long start = START_TIME.get();
        if (start == null) {
            return 0;
        }
        try {
            return System.nanoTime() - start;
        } finally {
            START_TIME.remove();
        }
    }

    public static long stopTimerMs() {
        return TimeUnit.NANOSECONDS.toMillis(stopTimer());
    }

    public static <T> TimedResult<T> timed(Supplier<T> supplier) {
        long start = System.nanoTime();
        T result = supplier.get();
        long durationNanos = System.nanoTime() - start;
        return new TimedResult<>(result, durationNanos);
    }

    public static <T> T withTracing(String operationName, Supplier<T> supplier) {
        long start = System.nanoTime();
        try {
            return supplier.get();
        } finally {
            long duration = System.nanoTime() - start;
            if (duration > TimeUnit.SECONDS.toNanos(1)) {
                System.out.printf("Slow operation: %s took %dms%n",
                        operationName, TimeUnit.NANOSECONDS.toMillis(duration));
            }
        }
    }

    public static long millisBetween(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return 0;
        }
        return Duration.between(start, end).toMillis();
    }

    public static long currentElapsedMs(LocalDateTime start) {
        return millisBetween(start, LocalDateTime.now());
    }

    public static boolean hasExceededTimeout(LocalDateTime start, long timeoutMs) {
        if (start == null || timeoutMs <= 0) {
            return false;
        }
        return currentElapsedMs(start) > timeoutMs;
    }

    public static long remainingTimeMs(LocalDateTime start, long timeoutMs) {
        long elapsed = currentElapsedMs(start);
        return Math.max(0, timeoutMs - elapsed);
    }

    public record TimedResult<T>(T result, long durationNanos) {
        public long durationMs() {
            return TimeUnit.NANOSECONDS.toMillis(durationNanos);
        }
    }
}
