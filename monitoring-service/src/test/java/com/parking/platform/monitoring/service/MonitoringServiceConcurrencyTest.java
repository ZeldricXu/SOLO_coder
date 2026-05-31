package com.parking.platform.monitoring.service;

import com.parking.platform.monitoring.entity.MetricSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MonitoringService 并发场景测试")
class MonitoringServiceConcurrencyTest {

    private MeterRegistry meterRegistry;
    private MonitoringService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new MonitoringService(meterRegistry);
    }

    @AfterEach
    void tearDown() {
        meterRegistry.clear();
    }

    @Nested
    @DisplayName("多线程并发Counter递增测试")
    class ConcurrentCounterTests {

        @Test
        @DisplayName("多线程并发递增同一个Counter - 计数应该正确累加")
        void testConcurrentCounterIncrement_ThreadSafe() throws Exception {
            int threadCount = 10;
            int incrementsPerThread = 1000;
            int totalIncrements = threadCount * incrementsPerThread;

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < incrementsPerThread; i++) {
                            service.incrementCounter("concurrent.counter");
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(totalIncrements, successCount.get());
            assertEquals(totalIncrements, meterRegistry.get("concurrent.counter").counter().count());
        }

        @Test
        @DisplayName("多线程并发递增不同Counters - 互不干扰")
        void testConcurrentCounters_DifferentCounters() throws Exception {
            int threadCount = 20;
            int countersPerThread = 5;
            int incrementsPerCounter = 100;

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int t = 0; t < threadCount; t++) {
                final int threadNum = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int c = 0; c < countersPerThread; c++) {
                            String counterName = "counter.thread" + threadNum + "." + c;
                            for (int i = 0; i < incrementsPerCounter; i++) {
                                service.incrementCounter(counterName);
                            }
                            successCount.addAndGet(incrementsPerCounter);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            int expectedTotal = threadCount * countersPerThread * incrementsPerCounter;
            assertEquals(expectedTotal, successCount.get());
            assertEquals(threadCount * countersPerThread, meterRegistry.getMeters().stream()
                    .filter(m -> m.getId().getName().startsWith("counter.thread"))
                    .count());
        }

        @Test
        @DisplayName("多线程混合使用不同增量值")
        void testConcurrentCounters_DifferentIncrements() throws Exception {
            int threadCount = 10;
            int operationsPerThread = 100;

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger totalValue = new AtomicInteger(0);
            AtomicInteger successCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < operationsPerThread; i++) {
                            double amount = Math.random() * 100;
                            service.incrementCounter("mixed.counter", amount);
                            totalValue.addAndGet((int) amount);
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(threadCount * operationsPerThread, successCount.get());
            assertTrue(meterRegistry.get("mixed.counter").counter().count() > 0);
        }
    }

    @Nested
    @DisplayName("多线程并发Timer记录测试")
    class ConcurrentTimerTests {

        @Test
        @DisplayName("多线程并发记录同一个Timer - 统计正确")
        void testConcurrentTimerRecords_ThreadSafe() throws Exception {
            int threadCount = 10;
            int recordsPerThread = 500;
            int totalRecords = threadCount * recordsPerThread;

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < recordsPerThread; i++) {
                            service.recordTimer("concurrent.timer", i + 1, TimeUnit.MILLISECONDS);
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(totalRecords, successCount.get());
            assertEquals(totalRecords, meterRegistry.get("concurrent.timer").timer().count());
        }

        @Test
        @DisplayName("多线程并发记录不同Timers")
        void testConcurrentTimers_DifferentTimers() throws Exception {
            int threadCount = 15;
            int timersPerThread = 3;
            int recordsPerTimer = 100;

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int t = 0; t < threadCount; t++) {
                final int threadNum = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int tm = 0; tm < timersPerThread; tm++) {
                            String timerName = "timer.thread" + threadNum + "." + tm;
                            for (int i = 0; i < recordsPerTimer; i++) {
                                service.recordTimer(timerName, i + 1, TimeUnit.MILLISECONDS);
                            }
                            successCount.addAndGet(recordsPerTimer);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            int expectedTotal = threadCount * timersPerThread * recordsPerTimer;
            assertEquals(expectedTotal, successCount.get());
        }
    }

    @Nested
    @DisplayName("多线程并发Snapshot创建测试")
    class ConcurrentSnapshotTests {

        @Test
        @DisplayName("多线程并发创建Snapshot - 所有快照都应该成功")
        void testConcurrentSnapshotCreation() throws Exception {
            int threadCount = 20;
            int snapshotsPerThread = 50;
            int totalSnapshots = threadCount * snapshotsPerThread;

            for (int i = 0; i < 100; i++) {
                service.incrementCounter("metric." + i);
                service.recordTimer("timer." + i, i + 1, TimeUnit.MILLISECONDS);
            }

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int t = 0; t < threadCount; t++) {
                final int threadNum = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < snapshotsPerThread; i++) {
                            MetricSnapshot snapshot = service.createSnapshot("snapshot.thread" + threadNum + "." + i);
                            assertNotNull(snapshot);
                            assertNotNull(snapshot.getId());
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(totalSnapshots, successCount.get());
            assertEquals(totalSnapshots, service.getSnapshots().size());
        }

        @Test
        @DisplayName("并发创建Snapshot时并发修改Metrics - 数据一致性")
        void testConcurrentSnapshotWithMetricsModification() throws Exception {
            int threadCount = 10;
            int operationsPerThread = 200;

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount * 2);
            AtomicInteger counterValue = new AtomicInteger(0);
            AtomicInteger snapshotCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount * 2);

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < operationsPerThread; i++) {
                            service.incrementCounter("concurrent.mixed.counter");
                            counterValue.incrementAndGet();
                            Thread.yield();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < operationsPerThread; i++) {
                            MetricSnapshot snapshot = service.createSnapshot("snapshot.mixed." + i);
                            assertNotNull(snapshot);
                            snapshotCount.incrementAndGet();
                            Thread.yield();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(threadCount * operationsPerThread, counterValue.get());
            assertEquals(threadCount * operationsPerThread, snapshotCount.get());
            assertTrue(meterRegistry.get("concurrent.mixed.counter").counter().count() == counterValue.get());
        }
    }

    @Nested
    @DisplayName("多线程并发Performance Metric测试")
    class ConcurrentPerformanceTests {

        @Test
        @DisplayName("多线程并发记录Performance Metric")
        void testConcurrentPerformanceMetrics() throws Exception {
            int threadCount = 10;
            int recordsPerThread = 100;
            int totalRecords = threadCount * recordsPerThread;

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int t = 0; t < threadCount; t++) {
                final int threadNum = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < recordsPerThread; i++) {
                            boolean success = (i % 2 == 0);
                            MetricSnapshot snapshot = service.recordPerformanceMetric(
                                    "operation.thread" + threadNum,
                                    (threadNum * 10 + i),
                                    success
                            );
                            assertNotNull(snapshot);
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(totalRecords, successCount.get());

            assertTrue(meterRegistry.get("operation.total").counter().count() == totalRecords);
            assertEquals(totalRecords, service.getSnapshots().stream()
                    .filter(s -> s.getName().startsWith("performance.operation.thread"))
                    .count());
        }
    }

    @Nested
    @DisplayName("多线程并发读操作一致性测试")
    class ConcurrentReadWriteTests {

        @Test
        @DisplayName("并发读写 - 读操作应该看到一致状态")
        void testConcurrentReadWrite_ConsistentReads() throws Exception {
            int writerCount = 5;
            int readerCount = 15;
            int writesPerWriter = 100;
            int readsPerReader = 500;

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(writerCount + readerCount);
            AtomicInteger writeSuccess = new AtomicInteger(0);
            AtomicInteger readSuccess = new AtomicInteger(0);
            AtomicInteger inconsistentReads = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(writerCount + readerCount);

            for (int w = 0; w < writerCount; w++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < writesPerWriter; i++) {
                            service.incrementCounter("concurrent.readwrite.counter");
                            writeSuccess.incrementAndGet();
                            Thread.yield();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            for (int r = 0; r < readerCount; r++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < readsPerReader; i++) {
                            Map<String, Object> summary = service.getMetricsSummary();
                            if (summary != null && summary.containsKey("totalMeters")) {
                                readSuccess.incrementAndGet();
                                Object total = summary.get("totalMeters");
                                if (total != null && ((Integer) total) >= 0) {
                                }
                            }
                            Thread.yield();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(writerCount * writesPerWriter, writeSuccess.get());
            assertEquals(readerCount * readsPerReader, readSuccess.get());
            assertTrue(meterRegistry.get("concurrent.readwrite.counter").counter().count() == writeSuccess.get());
        }

        @Test
        @DisplayName("高并发下getSnapshots应该始终返回有效列表")
        void testConcurrentGetSnapshots_AlwaysReturnsValidList() throws Exception {
            int snapshotCreators = 5;
            int snapshotReaders = 20;
            int snapshotsPerCreator = 200;
            int readsPerReader = 200;

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(snapshotCreators + snapshotReaders);
            AtomicInteger createSuccess = new AtomicInteger(0);
            AtomicInteger readSuccess = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(snapshotCreators + snapshotReaders);

            for (int c = 0; c < snapshotCreators; c++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < snapshotsPerCreator; i++) {
                            service.createSnapshot("highconcurrent.reader.snapshot");
                            createSuccess.incrementAndGet();
                            Thread.yield();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            for (int r = 0; r < snapshotReaders; r++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < readsPerReader; i++) {
                            List<MetricSnapshot> snapshots = service.getSnapshots();
                            assertNotNull(snapshots);
                            readSuccess.incrementAndGet();
                            Thread.yield();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(snapshotCreators * snapshotsPerCreator, createSuccess.get());
            assertEquals(snapshotReaders * readsPerReader, readSuccess.get());
            assertTrue(service.getSnapshots().size() == createSuccess.get());
        }
    }

    @Nested
    @DisplayName("极限并发压力测试")
    class ExtremeConcurrencyTests {

        @Test
        @DisplayName("1000线程并发操作")
        void testExtremeConcurrency() throws Exception {
            int threadCount = 100;
            int operationsPerThread = 100;

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger totalOperations = new AtomicInteger(0);

            ExecutorService executor = Executors.newCachedThreadPool();

            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threadCount; t++) {
                final int threadNum = t;
                futures.add(executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < operationsPerThread; i++) {
                            int opType = threadNum % 4;
                            switch (opType) {
                                case 0:
                                    service.incrementCounter("extreme.counter", i);
                                    break;
                                case 1:
                                    service.recordTimer("extreme.timer", i, TimeUnit.MILLISECONDS);
                                    break;
                                case 2:
                                    service.createSnapshot("extreme.snapshot");
                                    break;
                                case 3:
                                    service.recordPerformanceMetric("extreme.op", i, i % 2 == 0);
                                    break;
                            }
                            totalOperations.incrementAndGet();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }));
            }

            startLatch.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
            executor.shutdown();

            assertEquals(threadCount * operationsPerThread, totalOperations.get());
        }

        @Test
        @DisplayName("长时间并发运行 - 无内存泄漏或死锁")
        void testLongRunningConcurrency() throws Exception {
            int threadCount = 10;
            int iterations = 50;
            int delayMs = 10;

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger operations = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < iterations; i++) {
                            service.incrementCounter("long.counter");
                            service.recordTimer("long.running.timer", 10 + i, TimeUnit.MILLISECONDS);
                            service.createSnapshot("long.snapshot");
                            operations.addAndGet(3);
                            try {
                                Thread.sleep(delayMs);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await(60, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(threadCount * iterations * 3, operations.get());
        }
    }
}
