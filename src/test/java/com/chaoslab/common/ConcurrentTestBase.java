package com.chaoslab.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

public abstract class ConcurrentTestBase extends BaseTest {

    protected static final int DEFAULT_THREAD_COUNT = 10;
    protected static final int DEFAULT_ITERATIONS = 100;
    protected static final long DEFAULT_TIMEOUT_SECONDS = 30;

    protected <T> void assertConcurrentSafety(Supplier<T> operation, int threadCount, int iterations)
            throws InterruptedException, ExecutionException, TimeoutException {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        List<Future<T>> futures = new ArrayList<>();
        List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>());

        try {
            for (int i = 0; i < threadCount; i++) {
                Future<T> future = executor.submit(() -> {
                    try {
                        startLatch.await();
                        List<T> results = new ArrayList<>();
                        for (int j = 0; j < iterations; j++) {
                            try {
                                T result = operation.get();
                                results.add(result);
                                successCount.incrementAndGet();
                            } catch (Throwable t) {
                                exceptions.add(t);
                                errorCount.incrementAndGet();
                            }
                        }
                        return results.isEmpty() ? null : results.get(results.size() - 1);
                    } finally {
                        endLatch.countDown();
                    }
                });
                futures.add(future);
            }

            long startTime = System.currentTimeMillis();
            startLatch.countDown();

            boolean completed = endLatch.await(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            long duration = System.currentTimeMillis() - startTime;

            if (!completed) {
                throw new TimeoutException("Concurrent test timed out after " + DEFAULT_TIMEOUT_SECONDS + "s");
            }

            for (Future<T> future : futures) {
                future.get(1, TimeUnit.SECONDS);
            }

            if (!exceptions.isEmpty()) {
                logConcurrentErrors(exceptions);
            }

            assertThat(successCount.get())
                    .as("Should have successful operations")
                    .isPositive();

            logConcurrentSummary(threadCount, iterations, successCount.get(), errorCount.get(), duration);

        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS),
                    "Executor did not terminate properly");
        }
    }

    protected <T> void assertConcurrentCorrectness(Supplier<T> operation,
                                                   java.util.function.Consumer<T> verifier,
                                                   int threadCount,
                                                   int iterations)
            throws InterruptedException, ExecutionException, TimeoutException {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>());
        List<T> allResults = Collections.synchronizedList(new ArrayList<>());

        try {
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int j = 0; j < iterations; j++) {
                            try {
                                T result = operation.get();
                                allResults.add(result);
                                if (verifier != null) {
                                    verifier.accept(result);
                                }
                            } catch (Throwable t) {
                                exceptions.add(t);
                            }
                        }
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            boolean completed = endLatch.await(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!completed) {
                throw new TimeoutException("Concurrent test timed out");
            }

            assertTrue(exceptions.isEmpty(),
                    "Concurrent operation threw exceptions: " + exceptions);

        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    protected void assertResourceRelease(Runnable acquire, Runnable release, int iterations)
            throws Exception {
        for (int i = 0; i < iterations; i++) {
            try {
                acquire.run();
            } finally {
                release.run();
            }
        }

        assertAllResourcesReleased();
    }

    protected void assertResourceReleaseConcurrent(Runnable acquire, Runnable release,
                                                   int threadCount, int iterations)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger errorCount = new AtomicInteger(0);

        try {
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int j = 0; j < iterations; j++) {
                            try {
                                acquire.run();
                            } finally {
                                try {
                                    release.run();
                                } catch (Exception e) {
                                    errorCount.incrementAndGet();
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            endLatch.await(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertEquals(0, errorCount.get(),
                    "Resource release errors occurred during concurrent execution");

            assertAllResourcesReleased();

        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    protected abstract void assertAllResourcesReleased();

    private void logConcurrentErrors(List<Throwable> exceptions) {
        System.err.println("=== Concurrent Operation Errors ===");
        for (int i = 0; i < Math.min(exceptions.size(), 10); i++) {
            System.err.println("Error " + (i + 1) + ": " + exceptions.get(i).getMessage());
        }
        if (exceptions.size() > 10) {
            System.err.println("... and " + (exceptions.size() - 10) + " more errors");
        }
    }

    private void logConcurrentSummary(int threadCount, int iterations,
                                      int successCount, int errorCount, long duration) {
        System.out.printf("=== Concurrent Test Summary ===%n" +
                        "Threads: %d, Iterations per thread: %d%n" +
                        "Total operations: %d, Successful: %d, Errors: %d%n" +
                        "Duration: %dms, Throughput: %.2f ops/sec%n",
                threadCount, iterations,
                threadCount * iterations, successCount, errorCount,
                duration, (successCount * 1000.0) / Math.max(duration, 1));
    }

    @Test
    @DisplayName("Test infrastructure should be properly initialized")
    void testInfrastructure() {
        assertNotNull(TestDataFactory.randomId("test"));
        assertFalse(TestDataFactory.randomId("test").isEmpty());
    }
}
