package com.datapipeline.common.test;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

public class TestUtils {

    private TestUtils() {}

    public static <T> List<T> executeConcurrently(int threadCount, Supplier<T> task) throws InterruptedException, ExecutionException {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            List<Callable<T>> callables = new CopyOnWriteArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                callables.add(task::get);
            }
            List<Future<T>> futures = executor.invokeAll(callables);
            List<T> results = new CopyOnWriteArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdown();
        }
    }

    public static <T> void executeConcurrently(int threadCount, int iterationsPerThread, Consumer<Integer> task) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < iterationsPerThread; j++) {
                        try {
                            task.accept(threadIndex * iterationsPerThread + j);
                            successCount.incrementAndGet();
                        } catch (Exception e) {
                            failureCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(failureCount.get()).as("All concurrent operations should succeed").isZero();
    }

    public static void assertWithin(long expected, long actual, long tolerance) {
        assertThat(Math.abs(actual - expected)).as("Value should be within tolerance")
                .isLessThanOrEqualTo(tolerance);
    }

    public static void assertEventually(Runnable assertion, long timeoutMs, long intervalMs) {
        long startTime = System.currentTimeMillis();
        AssertionError lastError = null;
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                assertion.run();
                return;
            } catch (AssertionError e) {
                lastError = e;
                sleepQuietly(intervalMs);
            }
        }
        if (lastError != null) {
            throw lastError;
        }
    }

    public static void assertEventually(Runnable assertion) {
        assertEventually(assertion, 5000, 100);
    }

    public static boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static <T extends Throwable> T assertThrows(Class<T> expectedType, Runnable runnable) {
        try {
            runnable.run();
        } catch (Throwable t) {
            if (expectedType.isInstance(t)) {
                return expectedType.cast(t);
            }
            throw new AssertionError("Expected exception of type " + expectedType.getName() +
                    " but got " + t.getClass().getName(), t);
        }
        throw new AssertionError("Expected exception of type " + expectedType.getName() + " but none was thrown");
    }

}
