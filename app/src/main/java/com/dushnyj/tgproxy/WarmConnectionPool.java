package com.dushnyj.tgproxy;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private final int perKeySize;
    private final Alive<T> alive;
    private final Closer<T> closer;
    private final Executor executor;
    private final ConcurrentHashMap<String, ArrayBlockingQueue<T>> queues = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> filling = new ConcurrentHashMap<>();

    WarmConnectionPool(int perKeySize, Alive<T> alive, Closer<T> closer) {
        this(perKeySize, alive, closer, Executors.newCachedThreadPool(new DaemonThreadFactory()));
    }

    WarmConnectionPool(int perKeySize, Alive<T> alive, Closer<T> closer, Executor executor) {
        this.perKeySize = Math.max(0, perKeySize);
        this.alive = alive;
        this.closer = closer;
        this.executor = executor;
    }

    T acquire(String key, Opener<T> opener) {
        ArrayBlockingQueue<T> queue = queues.get(key);
        if (queue != null) {
            T value;
            while ((value = queue.poll()) != null) {
                if (alive.isAlive(value)) return value;
                closer.close(value);
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
            ArrayBlockingQueue<T> queue = queueFor(key);
            if (queue.size() >= perKeySize) continue;
            AtomicBoolean flag = filling.computeIfAbsent(key, ignored -> new AtomicBoolean(false));
            if (!flag.compareAndSet(false, true)) continue;
            executor.execute(() -> {
                try {
                    while (queue.size() < perKeySize) {
                        T value = opener.open(key);
                        if (value == null) break;
                        if (!queue.offer(value)) {
                            closer.close(value);
                            break;
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    flag.set(false);
                }
            });
        }
    }

    void clear() {
        for (ArrayBlockingQueue<T> queue : queues.values()) {
            T value;
            while ((value = queue.poll()) != null) {
                closer.close(value);
            }
        }
        queues.clear();
        filling.clear();
    }

    int idleCount() {
        int count = 0;
        for (ArrayBlockingQueue<T> queue : queues.values()) {
            count += queue.size();
        }
        return count;
    }

    private ArrayBlockingQueue<T> queueFor(String key) {
        return queues.computeIfAbsent(key, ignored -> new ArrayBlockingQueue<>(Math.max(1, perKeySize)));
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private int nextId = 1;

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "tg-ws-warmup-" + nextId++);
            thread.setDaemon(true);
            return thread;
        }
    }
}
