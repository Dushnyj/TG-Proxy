package com.dushnyj.tgproxy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;

/** Gives the preferred route a short head start, then races bounded fallbacks. */
final class ConnectionRacer<T> {
    interface Cancellation {
        boolean isCancelled();

        default void onCancel(Runnable callback) {
        }
    }

    interface Attempt<T> {
        T connect(Cancellation cancellation) throws Exception;
    }

    interface Closer<T> {
        void close(T value);
    }

    static final class Candidate<T> {
        final Attempt<T> attempt;

        Candidate(Attempt<T> attempt) {
            this.attempt = attempt;
        }
    }

    T connect(List<Candidate<T>> candidates, int parallelism, long staggerMs,
              ConnectBudget budget, Closer<T> closer) {
        if (candidates == null || candidates.isEmpty() || budget == null || !budget.hasTime()) {
            return null;
        }
        int limit = Math.max(1, Math.min(parallelism, candidates.size()));
        ExecutorService executor = Executors.newFixedThreadPool(limit, new DaemonThreadFactory());
        CompletionService<T> completion = new ExecutorCompletionService<>(executor);
        ArrayList<Future<T>> futures = new ArrayList<>();
        ArrayList<CancellationToken> cancellations = new ArrayList<>();
        AtomicReference<T> chosen = new AtomicReference<>();
        AtomicReference<CancellationToken> chosenCancellation = new AtomicReference<>();
        AtomicBoolean acceptingWinner = new AtomicBoolean(true);
        boolean delivered = false;
        int next = 0;
        int running = 0;
        long nextLaunchNanos = System.nanoTime();
        try {
            CancellationToken firstCancellation = new CancellationToken();
            cancellations.add(firstCancellation);
            futures.add(completion.submit(task(candidates.get(next++), chosen,
                    chosenCancellation, acceptingWinner, closer, firstCancellation)));
            running++;
            nextLaunchNanos = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, staggerMs));

            while (running > 0 && budget.hasTime()) {
                long waitMs = budget.remainingTimeoutMs(Integer.MAX_VALUE);
                if (waitMs <= 0) break;
                if (next < candidates.size() && running < limit && chosen.get() == null) {
                    long untilLaunchNanos = nextLaunchNanos - System.nanoTime();
                    if (untilLaunchNanos <= 0L) {
                        CancellationToken cancellation = new CancellationToken();
                        cancellations.add(cancellation);
                        futures.add(completion.submit(task(candidates.get(next++), chosen,
                                chosenCancellation, acceptingWinner, closer, cancellation)));
                        running++;
                        nextLaunchNanos = System.nanoTime()
                                + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, staggerMs));
                        continue;
                    }
                    long untilLaunchMs = TimeUnit.NANOSECONDS.toMillis(untilLaunchNanos);
                    waitMs = Math.min(waitMs, Math.max(1L, untilLaunchMs));
                }

                Future<T> completed = completion.poll(waitMs, TimeUnit.MILLISECONDS);
                if (completed == null) continue;
                running--;
                T value = completed.get();
                if (value != null) {
                    delivered = true;
                    acceptingWinner.set(false);
                    chosen.compareAndSet(value, null);
                    return value;
                }
                if (chosen.get() == null && next < candidates.size() && running < limit) {
                    CancellationToken cancellation = new CancellationToken();
                    cancellations.add(cancellation);
                    futures.add(completion.submit(task(candidates.get(next++), chosen,
                            chosenCancellation, acceptingWinner, closer, cancellation)));
                    running++;
                    nextLaunchNanos = System.nanoTime()
                            + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, staggerMs));
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException ignored) {
        } finally {
            acceptingWinner.set(false);
            CancellationToken deliveredCancellation = delivered ? chosenCancellation.get() : null;
            for (CancellationToken cancellation : cancellations) {
                if (cancellation != deliveredCancellation) cancellation.cancel();
            }
            if (!delivered) {
                T value = chosen.getAndSet(null);
                if (value != null) closer.close(value);
            }
            for (Future<T> future : futures) future.cancel(true);
            executor.shutdownNow();
        }
        return null;
    }

    private Callable<T> task(Candidate<T> candidate, AtomicReference<T> chosen,
                             AtomicReference<CancellationToken> chosenCancellation,
                             AtomicBoolean acceptingWinner, Closer<T> closer,
                             CancellationToken cancellation) {
        return () -> {
            T value;
            try {
                value = candidate == null || candidate.attempt == null
                        ? null : candidate.attempt.connect(new Cancellation() {
                            @Override public boolean isCancelled() {
                                return cancellation.isCancelled()
                                        || Thread.currentThread().isInterrupted()
                                        || !acceptingWinner.get()
                                        || chosen.get() != null;
                            }

                            @Override public void onCancel(Runnable callback) {
                                cancellation.onCancel(callback);
                            }
                        });
            } catch (Exception ignored) {
                return null;
            }
            if (value == null) return null;
            if (Thread.currentThread().isInterrupted()
                    || !acceptingWinner.get()
                    || !chosen.compareAndSet(null, value)) {
                closer.close(value);
                return null;
            }
            chosenCancellation.compareAndSet(null, cancellation);
            if (!acceptingWinner.get()) {
                if (chosen.compareAndSet(value, null)) {
                    chosenCancellation.compareAndSet(cancellation, null);
                    closer.close(value);
                }
                return null;
            }
            return value;
        };
    }

    private static final class CancellationToken implements Cancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final CopyOnWriteArrayList<Runnable> callbacks = new CopyOnWriteArrayList<>();

        @Override public boolean isCancelled() {
            return cancelled.get();
        }

        @Override public void onCancel(Runnable callback) {
            if (callback == null) return;
            if (cancelled.get()) {
                callback.run();
                return;
            }
            callbacks.add(callback);
            if (cancelled.get() && callbacks.remove(callback)) callback.run();
        }

        void cancel() {
            if (!cancelled.compareAndSet(false, true)) return;
            for (Runnable callback : callbacks) {
                try { callback.run(); } catch (RuntimeException ignored) {}
            }
            callbacks.clear();
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private int nextId = 1;

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "tg-route-connect-" + nextId++);
            thread.setDaemon(true);
            return thread;
        }
    }
}
