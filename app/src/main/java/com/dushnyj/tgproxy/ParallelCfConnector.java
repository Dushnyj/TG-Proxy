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
import java.util.concurrent.atomic.AtomicBoolean;

final class ParallelCfConnector<T> {
    interface Attempt<T> {
        T connect(String baseDomain) throws Exception;
    }

    interface Closer<T> {
        void close(T value);
    }

    private final CfProxyDomainState domainState;
    private final int parallelism;
    private final String networkProfile;

    ParallelCfConnector(CfProxyDomainState domainState, int parallelism) {
        this(domainState, parallelism, CfProxyDomainState.PROFILE_DEFAULT);
    }

    ParallelCfConnector(CfProxyDomainState domainState, int parallelism, String networkProfile) {
        this.domainState = domainState;
        this.parallelism = Math.max(1, parallelism);
        this.networkProfile = networkProfile;
    }

    T connect(List<String> domains, Attempt<T> attempt, Closer<T> closer) {
        List<String> ordered = domainState.orderedDomains(
                domains, networkProfile, System.currentTimeMillis());
        if (ordered.isEmpty()) return null;

        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(parallelism, ordered.size()),
                new DaemonThreadFactory());
        CompletionService<Result<T>> completion = new ExecutorCompletionService<>(executor);
        ArrayList<Future<Result<T>>> futures = new ArrayList<>();
        AtomicBoolean winnerChosen = new AtomicBoolean(false);

        int next = 0;
        int running = 0;
        int limit = Math.min(parallelism, ordered.size());
        try {
            while (running < limit && next < ordered.size()) {
                futures.add(completion.submit(task(ordered.get(next++), attempt, closer, winnerChosen)));
                running++;
            }

            while (running > 0) {
                Future<Result<T>> future = completion.take();
                running--;
                Result<T> result = future.get();
                if (result.value != null) {
                    domainState.markSuccess(result.domain, networkProfile, System.currentTimeMillis());
                    cancelOthers(futures, future);
                    return result.value;
                }
                if (CfProxyDomainState.isTooManyRequests(result.error)) {
                    domainState.markTooManyRequests(
                            result.domain, networkProfile, System.currentTimeMillis());
                }
                if (!winnerChosen.get() && next < ordered.size()) {
                    futures.add(completion.submit(task(ordered.get(next++), attempt, closer, winnerChosen)));
                    running++;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException ignored) {
        } finally {
            executor.shutdownNow();
        }
        return null;
    }

    private Callable<Result<T>> task(String domain, Attempt<T> attempt, Closer<T> closer,
                                    AtomicBoolean winnerChosen) {
        return () -> {
            try {
                T value = attempt.connect(domain);
                if (value == null) return Result.failure(domain, null);
                if (!winnerChosen.compareAndSet(false, true) || Thread.currentThread().isInterrupted()) {
                    closer.close(value);
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
