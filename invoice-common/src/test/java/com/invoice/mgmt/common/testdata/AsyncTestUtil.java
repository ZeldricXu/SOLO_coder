package com.invoice.mgmt.common.testdata;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class AsyncTestUtil {

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();

    public static <T> Future<T> submitAsync(Supplier<T> task) {
        return EXECUTOR.submit(task::get);
    }

    public static void runAsync(Runnable task) {
        EXECUTOR.submit(task);
    }

    public static <T> T waitForResult(Future<T> future, long timeoutMs) throws Exception {
        return future.get(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public static boolean waitForCondition(Supplier<Boolean> condition, long timeoutMs) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (condition.get()) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    public static <T> List<T> submitParallelTasks(List<Supplier<T>> tasks) throws Exception {
        List<Future<T>> futures = new ArrayList<>();
        for (Supplier<T> task : tasks) {
            futures.add(EXECUTOR.submit(task::get));
        }
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get());
        }
        return results;
    }

    public static int measureExecutionTimeMs(Runnable task) {
        long startTime = System.currentTimeMillis();
        task.run();
        return (int) (System.currentTimeMillis() - startTime);
    }

    public static <T> int measureExecutionTimeMs(Supplier<T> task) {
        long startTime = System.currentTimeMillis();
        task.get();
        return (int) (System.currentTimeMillis() - startTime);
    }

    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static CountDownLatch createLatch(int count) {
        return new CountDownLatch(count);
    }

    public static boolean awaitLatch(CountDownLatch latch, long timeoutMs) {
        try {
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            return false;
        }
    }

    public static class AtomicCounter {
        private final AtomicInteger count = new AtomicInteger(0);

        public int increment() {
            return count.incrementAndGet();
        }

        public int decrement() {
            return count.decrementAndGet();
        }

        public int get() {
            return count.get();
        }

        public void reset() {
            count.set(0);
        }
    }

    public static void shutdown() {
        EXECUTOR.shutdown();
    }
}
