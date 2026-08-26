package com.dushnyj.tgproxy;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ParallelCfConnectorTest {

    @Test(timeout = 800)
    public void returnsFastSuccessfulDomainWithoutWaitingForSlowFirstDomain() throws Exception {
        CfProxyDomainState state = new CfProxyDomainState(45_000);
        CountDownLatch slowStarted = new CountDownLatch(1);

        ParallelCfConnector<String> connector = new ParallelCfConnector<>(state, 2);
        String result = connector.connect(
                Arrays.asList("slow.example", "fast.example"),
                domain -> {
                    if ("slow.example".equals(domain)) {
                        slowStarted.countDown();
                        Thread.sleep(5_000);
                        throw new IOException("slow failed");
                    }
                    assertTrue(slowStarted.await(200, TimeUnit.MILLISECONDS));
                    return domain;
                },
                value -> {});

        assertEquals("fast.example", result);
        assertEquals(Arrays.asList("fast.example", "slow.example"),
                state.orderedDomains(Arrays.asList("slow.example", "fast.example"), 10_000));
    }

    @Test
    public void storesSuccessfulDomainForConnectorNetworkProfile() {
        CfProxyDomainState state = new CfProxyDomainState(45_000);

        new ParallelCfConnector<String>(state, 1, CfProxyDomainState.PROFILE_WIFI)
                .connect(Arrays.asList("wifi.example", "mobile.example"),
                        domain -> domain,
                        value -> {});
        new ParallelCfConnector<String>(state, 1, CfProxyDomainState.PROFILE_MOBILE)
                .connect(Arrays.asList("mobile.example", "wifi.example"),
                        domain -> domain,
                        value -> {});

        assertEquals(Arrays.asList("wifi.example", "mobile.example"),
                state.orderedDomains(Arrays.asList(
                        "mobile.example",
                        "wifi.example"), CfProxyDomainState.PROFILE_WIFI, 10_000));
        assertEquals(Arrays.asList("mobile.example", "wifi.example"),
                state.orderedDomains(Arrays.asList(
                        "wifi.example",
                        "mobile.example"), CfProxyDomainState.PROFILE_MOBILE, 10_000));
    }

    @Test(timeout = 800)
    public void sharedBudgetStopsWaitingForAllDomains() {
        CfProxyDomainState state = new CfProxyDomainState(45_000);
        long started = System.nanoTime();

        String result = new ParallelCfConnector<String>(state, 2)
                .connect(Arrays.asList("slow-a.example", "slow-b.example"),
                        domain -> {
                            Thread.sleep(5_000L);
                            return domain;
                        },
                        value -> {},
                        new ConnectBudget(100L));

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertEquals(null, result);
        assertTrue("elapsed=" + elapsedMs, elapsedMs < 500L);
    }

    @Test(timeout = 1_500)
    public void lateSuccessAfterBudgetIsClosed() throws Exception {
        CfProxyDomainState state = new CfProxyDomainState(45_000);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger closed = new AtomicInteger();

        String result = new ParallelCfConnector<String>(state, 1)
                .connect(Arrays.asList("late.example"),
                        domain -> {
                            started.countDown();
                            while (true) {
                                try {
                                    release.await();
                                    return domain;
                                } catch (InterruptedException ignored) {
                                    // Model socket I/O which completes after Future.cancel(true).
                                }
                            }
                        },
                        value -> closed.incrementAndGet(),
                        new ConnectBudget(50L));

        assertTrue(started.await(200L, TimeUnit.MILLISECONDS));
        assertEquals(null, result);
        release.countDown();
        long deadline = System.currentTimeMillis() + 1_000L;
        while (closed.get() == 0 && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10L);
        }
        assertEquals(1, closed.get());
    }
}
