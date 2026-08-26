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

final class ParallelCfConnector<T> {
    interface Attempt<T> {
        T connect(String baseDomain) throws Exception;
    }

    interface Closer<T> {
        void close(T value);
    }

    interface FailureListener {
        void onFailure(String baseDomain, Exception error);
    }

    private final CfProxyDomainState domainState;
    private final int parallelism;
    private final String networkProfile;
    private final FailureListener failureListener;

    ParallelCfConnector(CfProxyDomainState domainState, int parallelism) {
        this(domainState, parallelism, CfProxyDomainState.PROFILE_DEFAULT);
    }

    ParallelCfConnector(CfProxyDomainState domainState, int parallelism, String networkProfile) {
        this(domainState, parallelism, networkProfile, null);
    }

    ParallelCfConnector(CfProxyDomainState domainState, int parallelism, String networkProfile,
                        FailureListener failureListener) {
        this.domainState = domainState;
        this.parallelism = Math.max(1, parallelism);
        this.networkProfile = networkProfile;
        this.failureListener = failureListener;
    }

    T connect(List<String> domains, Attempt<T> attempt, Closer<T> closer) {
        return connect(domains, attempt, closer, null);
    }

    T connect(List<String> domains, Attempt<T> attempt, Closer<T> closer,
              ConnectBudget budget) {
        List<String> ordered = domainState.orderedDomains(
                domains, networkProfile, System.currentTimeMillis());
        if (ordered.isEmpty()) return null;

        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(parallelism, ordered.size()),
                new DaemonThreadFactory());
        CompletionService<Result<T>> completion = new ExecutorCompletionService<>(executor);
        ArrayList<Future<Result<T>>> futures = new ArrayList<>();
        AtomicReference<T> chosen = new AtomicReference<>();
        AtomicBoolean acceptingWinner = new AtomicBoolean(true);
        boolean delivered = false;

        int next = 0;
        int running = 0;
        int limit = Math.min(parallelism, ordered.size());
        try {
            while (running < limit && next < ordered.size()) {
                futures.add(completion.submit(task(ordered.get(next++), attempt, closer,
                        chosen, acceptingWinner)));
                running++;
            }

            while (running > 0) {
                Future<Result<T>> future;
                if (budget == null) {
                    future = completion.take();
                } else {
                    int waitMs = budget.remainingTimeoutMs(Integer.MAX_VALUE);
                    if (waitMs <= 0) break;
                    future = completion.poll(waitMs, TimeUnit.MILLISECONDS);
                    if (future == null) break;
                }
                running--;
                Result<T> result = future.get();
                if (result.value != null) {
                    delivered = true;
                    acceptingWinner.set(false);
                    chosen.compareAndSet(result.value, null);
                    domainState.markSuccess(result.domain, networkProfile, System.currentTimeMillis());
                    cancelOthers(futures, future);
                    return result.value;
                }
                if (failureListener != null) {
                    failureListener.onFailure(result.domain, result.error);
                }
                if (CfProxyDomainState.isTooManyRequests(result.error)) {
                    domainState.markTooManyRequests(
                            result.domain, networkProfile, System.currentTimeMillis());
                }
                if (chosen.get() == null && next < ordered.size()) {
                    futures.add(completion.submit(task(ordered.get(next++), attempt, closer,
                            chosen, acceptingWinner)));
                    running++;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException ignored) {
        } finally {
            acceptingWinner.set(false);
            if (!delivered) {
                T orphan = chosen.getAndSet(null);
                if (orphan != null) closer.close(orphan);
            }
            cancelOthers(futures, null);
            executor.shutdownNow();
        }
        return null;
    }

    private Callable<Result<T>> task(String domain, Attempt<T> attempt, Closer<T> closer,
                                    AtomicReference<T> chosen,
                                    AtomicBoolean acceptingWinner) {
        return () -> {
            try {
                T value = attempt.connect(domain);
                if (value == null) return Result.failure(domain, null);
                if (Thread.currentThread().isInterrupted()
                        || !acceptingWinner.get()
                        || !chosen.compareAndSet(null, value)) {
                    closer.close(value);
                    return Result.failure(domain, null);
                }
                if (!acceptingWinner.get()) {
                    if (chosen.compareAndSet(value, null)) closer.close(value);
                    return Result.failure(domain, null);
                }
                return Result.success(domain, value);
            } catch (Exception e) {
                return Result.failure(domain, e);
            }
        };
    }

    private void cancelOthers(List<Future<Result<T>>> futures, Future<Result<T>> winner) {
        for (Future<Result<T>> future : futures) {
            if (future != winner) future.cancel(true);
        }
    }

    private static final class Result<T> {
        final String domain;
        final T value;
        final Exception error;

        private Result(String domain, T value, Exception error) {
            this.domain = domain;
            this.value = value;
            this.error = error;
        }

        static <T> Result<T> success(String domain, T value) {
            return new Result<>(domain, value, null);
        }

        static <T> Result<T> failure(String domain, Exception error) {
            return new Result<>(domain, null, error);
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private int nextId = 1;

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "tg-cf-connect-" + nextId++);
            thread.setDaemon(true);
            return thread;
        }
    }
}
