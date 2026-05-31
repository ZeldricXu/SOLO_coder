package com.solocoder.base;

import org.awaitility.Awaitility;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

public class ConcurrentTestUtils {

    public static <T> List<T> executeConcurrently(int threadCount, int iterations,
                                                   Callable<T> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<T>> futures = new ArrayList<>();
        List<T> results = new CopyOnWriteArrayList<>();

        try {
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount * iterations);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount * iterations; i++) {
                futures.add(executor.submit(() -> {
                    startLatch.await();
                    try {
                        T result = task.call();
                        successCount.incrementAndGet();
                        return result;
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                        throw e;
                    } finally {
                        doneLatch.countDown();
                    }
                }));
            }

            startLatch.countDown();

            boolean completed = doneLatch.await(TestConstants.CONCURRENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertTrue(completed, "Concurrent test timed out");

            for (Future<T> future : futures) {
                try {
                    results.add(future.get(1, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                }
            }

            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    public static void executeConcurrentlyAndVerify(int threadCount, int iterations,
                                                    Callable<?> task,
                                                    double expectedSuccessRate) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();

        try {
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount * iterations; i++) {
                futures.add(executor.submit(() -> {
                    startLatch.await();
                    try {
                        task.call();
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                    }
                }));
            }

            startLatch.countDown();

            Awaitility.await()
                    .atMost(Duration.ofSeconds(TestConstants.CONCURRENT_TIMEOUT_SECONDS))
                    .until(() -> successCount.get() + failureCount.get() >= threadCount * iterations);

            int totalOperations = successCount.get() + failureCount.get();
            double actualSuccessRate = (double) successCount.get() / totalOperations;

            assertTrue(actualSuccessRate >= expectedSuccessRate,
                    String.format("Success rate %.2f%% is below expected %.2f%%. Success: %d, Failure: %d",
                            actualSuccessRate * 100, expectedSuccessRate * 100,
                            successCount.get(), failureCount.get()));

        } finally {
            executor.shutdownNow();
        }
    }

    public static void assertNoDataCorruption(Supplier<Integer> actualCountSupplier,
                                              int expectedMinCount) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .until(() -> actualCountSupplier.get() >= expectedMinCount);

        int actualCount = actualCountSupplier.get();
        assertTrue(actualCount >= expectedMinCount,
                "Expected at least " + expectedMinCount + " records but found " + actualCount);
    }

    public static void assertThreadSafety(Runnable task, int iterations) {
        AtomicInteger counter = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(10);
        for (int i = 0; i < iterations; i++) {
            executor.submit(() -> {
                task.run();
                counter.incrementAndGet();
            });
        }

        executor.shutdown();
        try {
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertEquals(iterations, counter.get());
    }

    private ConcurrentTestUtils() {
    }
}
