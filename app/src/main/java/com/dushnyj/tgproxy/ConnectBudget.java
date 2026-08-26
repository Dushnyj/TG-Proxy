package com.dushnyj.tgproxy;

import java.util.concurrent.TimeUnit;

/** One deadline shared by every fallback attempted for a single Telegram connection. */
final class ConnectBudget {
    interface NanoClock {
        long nanoTime();
    }

    private final NanoClock clock;
    private final long deadlineNanos;

    ConnectBudget(long budgetMs) {
        this(budgetMs, System::nanoTime);
    }

    ConnectBudget(long budgetMs, NanoClock clock) {
        this.clock = clock == null ? System::nanoTime : clock;
        long duration = TimeUnit.MILLISECONDS.toNanos(Math.max(1L, budgetMs));
        long now = this.clock.nanoTime();
        this.deadlineNanos = saturatingAdd(now, duration);
    }

    private ConnectBudget(NanoClock clock, long deadlineNanos) {
        this.clock = clock;
        this.deadlineNanos = deadlineNanos;
    }

    int remainingTimeoutMs(int perAttemptCapMs) {
        long remainingNanos = deadlineNanos - clock.nanoTime();
        if (remainingNanos <= 0L) return 0;
        long remainingMs = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
        if (remainingMs <= 0L) remainingMs = 1L;
        return (int) Math.min(Math.max(1, perAttemptCapMs),
                Math.min(Integer.MAX_VALUE, remainingMs));
    }

    boolean hasTime() {
        return deadlineNanos - clock.nanoTime() > 0L;
    }

    /** A child shares the same monotonic clock and can never outlive its parent. */
    ConnectBudget child(long maximumMs) {
        long now = clock.nanoTime();
        long duration = TimeUnit.MILLISECONDS.toNanos(Math.max(1L, maximumMs));
        return new ConnectBudget(clock, Math.min(deadlineNanos, saturatingAdd(now, duration)));
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }
}
