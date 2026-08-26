package com.dushnyj.tgproxy;

import org.junit.Test;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ConnectionRacerTest {
    @Test
    public void preferredRouteWinsBeforeFallbackIsStarted() {
        AtomicInteger fallbackStarts = new AtomicInteger();
        ConnectionRacer<String> racer = new ConnectionRacer<>();

        String winner = racer.connect(Arrays.asList(
                        new ConnectionRacer.Candidate<>(cancellation -> "preferred"),
                        new ConnectionRacer.Candidate<>(cancellation -> {
                            fallbackStarts.incrementAndGet();
                            return "fallback";
                        })),
                2, 100, new ConnectBudget(1_000), value -> {});

        assertEquals("preferred", winner);
        assertEquals(0, fallbackStarts.get());
    }

    @Test
    public void fastFallbackWinsAfterPreferredHeadStartAndLateWinnerIsClosed() throws Exception {
        CountDownLatch releasePreferred = new CountDownLatch(1);
        AtomicInteger closed = new AtomicInteger();
        AtomicBoolean cancellationObserved = new AtomicBoolean();
        ConnectionRacer<String> racer = new ConnectionRacer<>();

        String winner = racer.connect(Arrays.asList(
                        new ConnectionRacer.Candidate<>(cancellation -> {
                            while (true) {
                                try {
                                    releasePreferred.await();
                                    break;
                                } catch (InterruptedException ignored) {
                                    // Simulate socket I/O that does not immediately honor Future.cancel().
                                }
                            }
                            cancellationObserved.set(cancellation.isCancelled());
                            return "preferred";
                        }),
                        new ConnectionRacer.Candidate<>(cancellation -> "fallback")),
                2, 20, new ConnectBudget(1_000), value -> closed.incrementAndGet());

        assertEquals("fallback", winner);
        releasePreferred.countDown();
        long deadline = System.currentTimeMillis() + 1_000L;
        while (closed.get() == 0 && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10L);
        }
        assertTrue("late successful route was not closed", closed.get() > 0);
        assertTrue("late route did not observe winner cancellation", cancellationObserved.get());
    }

    @Test
    public void winnerCancellationCallbackIsPreservedWhileLoserSocketIsCancelled() throws Exception {
        CountDownLatch loserCancelled = new CountDownLatch(1);
        AtomicBoolean winnerCancelled = new AtomicBoolean(false);
        ConnectionRacer<String> racer = new ConnectionRacer<>();

        String winner = racer.connect(Arrays.asList(
                        new ConnectionRacer.Candidate<>(cancellation -> {
                            cancellation.onCancel(loserCancelled::countDown);
                            while (!cancellation.isCancelled()) {
                                TimeUnit.MILLISECONDS.sleep(5);
                            }
                            return null;
                        }),
                        new ConnectionRacer.Candidate<>(cancellation -> {
                            cancellation.onCancel(() -> winnerCancelled.set(true));
                            return "winner";
                        })),
                2, 10, new ConnectBudget(1_000), value -> {});

        assertEquals("winner", winner);
        assertTrue("loser cancellation callback did not run",
                loserCancelled.await(1, TimeUnit.SECONDS));
        assertTrue("delivered winner was cancelled", !winnerCancelled.get());
    }
}
