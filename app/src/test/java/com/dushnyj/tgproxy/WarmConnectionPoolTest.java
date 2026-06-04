package com.dushnyj.tgproxy;

import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

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
}
