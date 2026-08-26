package com.dushnyj.tgproxy;

import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class WarmConnectionPoolTest {

    @Test
    public void acquireReturnsWarmedConnectionBeforeOpeningNewOne() {
        AtomicInteger opened = new AtomicInteger();
        Executor directExecutor = Runnable::run;
        WarmConnectionPool<String> pool = new WarmConnectionPool<>(
                1,
                value -> true,
                value -> {},
                directExecutor);

        pool.warmup(Collections.singletonList("wifi:2"),
                key -> "warm-" + opened.incrementAndGet());

        String warmed = pool.acquire("wifi:2", key -> {
            fail("Expected warmed connection");
            return "new";
        });
        String fresh = pool.acquire("wifi:2", key -> "fresh-" + opened.incrementAndGet());

        assertEquals("warm-1", warmed);
        assertEquals("fresh-2", fresh);
    }

    @Test
    public void warmupDoesNotOverfillKeyQueue() {
        AtomicInteger opened = new AtomicInteger();
        Executor directExecutor = Runnable::run;
        WarmConnectionPool<String> pool = new WarmConnectionPool<>(
                1,
                value -> true,
                value -> {},
                directExecutor);

        pool.warmup(Collections.singletonList("wifi:2"),
                key -> "warm-" + opened.incrementAndGet());
        pool.warmup(Collections.singletonList("wifi:2"),
                key -> "warm-" + opened.incrementAndGet());

        assertEquals(1, opened.get());
        assertEquals(1, pool.idleCount());
    }

    @Test
    public void expiredWarmConnectionIsClosedAndNeverReturned() {
        AtomicInteger closed = new AtomicInteger();
        AtomicLong now = new AtomicLong(1_000L);
        Executor directExecutor = Runnable::run;
        WarmConnectionPool<String> pool = new WarmConnectionPool<>(
                1, 20_000L, value -> true, value -> closed.incrementAndGet(),
                directExecutor, now::get);
        pool.warmup(Collections.singletonList("wifi:2"), key -> "stale");
        now.set(21_001L);

        String value = pool.acquire("wifi:2", key -> "fresh");

        assertEquals("fresh", value);
        assertEquals(1, closed.get());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void clearClosesConnectionOpenedByAnObsoleteWarmupTask() {
        AtomicInteger closed = new AtomicInteger();
        WarmConnectionPool<String>[] holder = new WarmConnectionPool[1];
        holder[0] = new WarmConnectionPool<>(
                1,
                value -> true,
                value -> closed.incrementAndGet(),
                Runnable::run);

        holder[0].warmup(Collections.singletonList("wifi:2"), key -> {
            holder[0].clear();
            return "opened-on-old-network";
        });

        assertEquals(1, closed.get());
        assertEquals(0, holder[0].idleCount());
    }
}
