package com.dushnyj.tgproxy;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class WarmConnectionPool<T> {
    interface Opener<T> {
        T open(String key) throws Exception;
    }

    interface Alive<T> {
        boolean isAlive(T value);
    }

    interface Closer<T> {
        void close(T value);
    }

    interface Clock {
        long nowMs();
    }

    private final int perKeySize;
    private final Alive<T> alive;
    private final Closer<T> closer;
    private final Executor executor;
    private final Clock clock;
    private final long maxIdleMs;
    private final ConcurrentHashMap<String, ArrayBlockingQueue<Entry<T>>> queues =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> filling = new ConcurrentHashMap<>();
    private final AtomicLong epoch = new AtomicLong(1L);

    WarmConnectionPool(int perKeySize, Alive<T> alive, Closer<T> closer) {
        this(perKeySize, Long.MAX_VALUE, alive, closer,
                boundedExecutor(), System::currentTimeMillis);
    }

    WarmConnectionPool(int perKeySize, long maxIdleMs, Alive<T> alive, Closer<T> closer) {
        this(perKeySize, maxIdleMs, alive, closer,
                boundedExecutor(), System::currentTimeMillis);
    }

    WarmConnectionPool(int perKeySize, Alive<T> alive, Closer<T> closer, Executor executor) {
        this(perKeySize, Long.MAX_VALUE, alive, closer, executor, System::currentTimeMillis);
    }

    WarmConnectionPool(int perKeySize, long maxIdleMs, Alive<T> alive, Closer<T> closer,
                       Executor executor, Clock clock) {
        this.perKeySize = Math.max(0, perKeySize);
        this.maxIdleMs = Math.max(0L, maxIdleMs);
        this.alive = alive;
        this.closer = closer;
        this.executor = executor;
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    T acquire(String key, Opener<T> opener) {
        ArrayBlockingQueue<Entry<T>> queue = queues.get(key);
        if (queue != null) {
            Entry<T> entry;
            while ((entry = queue.poll()) != null) {
                if (entry.epoch == epoch.get()
                        && !isExpired(entry) && alive.isAlive(entry.value)) return entry.value;
                closer.close(entry.value);
            }
        }

        try {
            return opener.open(key);
        } catch (Exception ignored) {
            return null;
        }
    }

    void warmup(List<String> keys, Opener<T> opener) {
        if (perKeySize <= 0 || keys == null) return;
        for (String key : keys) {
            if (key == null || key.trim().isEmpty()) continue;
            ArrayBlockingQueue<Entry<T>> queue = queueFor(key);
            if (queue.size() >= perKeySize) continue;
            AtomicBoolean flag = fillingFlagFor(key);
            if (!flag.compareAndSet(false, true)) continue;
            long fillEpoch = epoch.get();
            try {
                executor.execute(() -> {
                    try {
                        while (fillEpoch == epoch.get() && queue.size() < perKeySize) {
                            T value = opener.open(key);
                            if (value == null) break;
                            if (fillEpoch != epoch.get()) {
                                closer.close(value);
                                break;
                            }
                            Entry<T> entry = new Entry<>(value, clock.nowMs(), fillEpoch);
                            if (!queue.offer(entry)) {
                                closer.close(value);
                                break;
                            }
                            // clear() may have raced between the epoch check and queue.offer().
                            if (fillEpoch != epoch.get() && queue.remove(entry)) {
                                closer.close(value);
                                break;
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        flag.set(false);
                    }
                });
            } catch (RejectedExecutionException saturated) {
                flag.set(false);
            }
        }
    }

    void clear() {
        epoch.incrementAndGet();
        for (ArrayBlockingQueue<Entry<T>> queue : queues.values()) {
            Entry<T> entry;
            while ((entry = queue.poll()) != null) {
                closer.close(entry.value);
            }
        }
        queues.clear();
    }

    int idleCount() {
        int count = 0;
        for (ArrayBlockingQueue<Entry<T>> queue : queues.values()) {
            count += queue.size();
        }
        return count;
    }

    private ArrayBlockingQueue<Entry<T>> queueFor(String key) {
        ArrayBlockingQueue<Entry<T>> existing = queues.get(key);
        if (existing != null) return existing;
        ArrayBlockingQueue<Entry<T>> created =
                new ArrayBlockingQueue<>(Math.max(1, perKeySize));
        ArrayBlockingQueue<Entry<T>> raced = queues.putIfAbsent(key, created);
        return raced == null ? created : raced;
    }

    private AtomicBoolean fillingFlagFor(String key) {
        AtomicBoolean existing = filling.get(key);
        if (existing != null) return existing;
        AtomicBoolean created = new AtomicBoolean(false);
        AtomicBoolean raced = filling.putIfAbsent(key, created);
        return raced == null ? created : raced;
    }

    private boolean isExpired(Entry<T> entry) {
        if (entry == null) return true;
        if (maxIdleMs == Long.MAX_VALUE) return false;
        long age = clock.nowMs() - entry.createdMs;
        return age < 0L || age > maxIdleMs;
    }

    private static final class Entry<T> {
        final T value;
        final long createdMs;
        final long epoch;

        Entry(T value, long createdMs, long epoch) {
            this.value = value;
            this.createdMs = createdMs;
            this.epoch = epoch;
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final AtomicInteger nextId = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable,
                    "tg-ws-warmup-" + nextId.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }

    private static Executor boundedExecutor() {
        return new ThreadPoolExecutor(2, 2, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32), new DaemonThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
