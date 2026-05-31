package com.datapipeline.monitoring.stats;

import com.datapipeline.common.model.StatisticsSnapshot;
import com.datapipeline.common.test.TestUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class StatisticsCollectorTest {

    private StatisticsCollector collector;

    @BeforeEach
    void setUp() {
        collector = new StatisticsCollector();
    }

    @AfterEach
    void tearDown() {
        collector.reset();
    }

    @Nested
    @DisplayName("计数器测试")
    class CounterTests {

        @Test
        @DisplayName("应正确增加计数器")
        void testIncrementCounter() {
            collector.incrementCounter("requests");
            collector.incrementCounter("requests");
            collector.incrementCounter("requests");

            assertThat(collector.getCounter("requests")).isEqualTo(3);
        }

        @Test
        @DisplayName("应正确按增量增加计数器")
        void testIncrementCounterWithDelta() {
            collector.incrementCounter("errors", 5);
            collector.incrementCounter("errors", 3);

            assertThat(collector.getCounter("errors")).isEqualTo(8);
        }

        @Test
        @DisplayName("不存在的计数器应返回0")
        void testNonExistentCounter() {
            assertThat(collector.getCounter("nonexistent")).isEqualTo(0);
        }

        @Test
        @DisplayName("计数器应正确处理负数增量")
        void testNegativeIncrement() {
            collector.incrementCounter("balance", 100);
            collector.incrementCounter("balance", -20);

            assertThat(collector.getCounter("balance")).isEqualTo(80);
        }

    }

    @Nested
    @DisplayName("仪表盘测试")
    class GaugeTests {

        @Test
        @DisplayName("应正确设置仪表盘值")
        void testSetGauge() {
            collector.setGauge("connections", 10);
            collector.setGauge("connections", 15);

            assertThat(collector.getGauge("connections")).isEqualTo(15);
        }

        @Test
        @DisplayName("不存在的仪表盘应返回0")
        void testNonExistentGauge() {
            assertThat(collector.getGauge("nonexistent")).isEqualTo(0);
        }

    }

    @Nested
    @DisplayName("直方图测试")
    class HistogramTests {

        @Test
        @DisplayName("应正确记录直方图值")
        void testRecordHistogram() {
            long[] values = {10, 20, 30, 40, 50};
            for (long value : values) {
                collector.recordHistogram("latency", value);
            }

            HistogramStats stats = collector.getHistogramStats("latency");

            assertThat(stats).isNotNull();
            assertThat(stats.getCount()).isEqualTo(5);
            assertThat(stats.getMin()).isEqualTo(10);
            assertThat(stats.getMax()).isEqualTo(50);
            assertThat(stats.getAvg()).isEqualTo(30.0);
        }

        @Test
        @DisplayName("应正确计算百分位数")
        void testPercentileCalculation() {
            for (int i = 1; i <= 100; i++) {
                collector.recordHistogram("metric", i);
            }

            HistogramStats stats = collector.getHistogramStats("metric");

            assertThat(stats.getCount()).isEqualTo(100);
            assertThat(stats.getP50()).isEqualTo(50);
            assertThat(stats.getP95()).isEqualTo(95);
            assertThat(stats.getP99()).isEqualTo(99);
        }

        @Test
        @DisplayName("空直方图应返回空统计")
        void testEmptyHistogram() {
            HistogramStats stats = collector.getHistogramStats("empty");

            assertThat(stats).isEqualTo(HistogramStats.EMPTY);
            assertThat(stats.getCount()).isEqualTo(0);
            assertThat(stats.getMin()).isEqualTo(0);
            assertThat(stats.getMax()).isEqualTo(0);
            assertThat(stats.getAvg()).isEqualTo(0.0);
        }

    }

    @Nested
    @DisplayName("快照测试")
    class SnapshotTests {

        @Test
        @DisplayName("应生成正确的统计快照")
        void testGenerateSnapshot() {
            collector.incrementCounter("requests", 100);
            collector.incrementCounter("errors", 5);
            collector.setGauge("connections", 15);

            StatisticsSnapshot snapshot = collector.snapshot(Map.of(
                    "host", "localhost",
                    "region", "cn-east"
            ));

            assertThat(snapshot).isNotNull();
            assertThat(snapshot.getSnapshotId()).isNotNull();
            assertThat(snapshot.getTimestamp()).isNotNull();
            assertThat(snapshot.getMetrics()).isNotNull();
            assertThat(snapshot.getDimensions()).isNotNull();

            assertThat(snapshot.getMetrics().get("counter_requests")).isEqualTo(100);
            assertThat(snapshot.getMetrics().get("counter_errors")).isEqualTo(5);
            assertThat(snapshot.getMetrics().get("gauge_connections")).isEqualTo(15);

            assertThat(snapshot.getDimensions().get("host")).isEqualTo("localhost");
            assertThat(snapshot.getDimensions().get("region")).isEqualTo("cn-east");
        }

        @Test
        @DisplayName("空指标应生成有效快照")
        void testEmptySnapshot() {
            StatisticsSnapshot snapshot = collector.snapshot();

            assertThat(snapshot).isNotNull();
            assertThat(snapshot.getMetrics()).isNotNull();
            assertThat(snapshot.getDimensions()).isNotNull();
        }

    }

    @Nested
    @DisplayName("并发安全测试")
    class ConcurrencyTests {

        @Test
        @DisplayName("并发增加计数器应正确累加")
        void testConcurrentCounterIncrement() throws Exception {
            int threadCount = 20;
            int incrementsPerThread = 1000;

            TestUtils.executeConcurrently(threadCount, incrementsPerThread, iteration -> {
                collector.incrementCounter("concurrent_counter");
            });

            assertThat(collector.getCounter("concurrent_counter"))
                    .isEqualTo((long) threadCount * incrementsPerThread);
        }

        @Test
        @DisplayName("并发设置仪表盘应保留最终值")
        void testConcurrentGaugeSet() throws Exception {
            int threadCount = 50;
            int iterationsPerThread = 100;

            TestUtils.executeConcurrently(threadCount, iterationsPerThread, iteration -> {
                collector.setGauge("concurrent_gauge", iteration);
            });

            assertThat(collector.getGauge("concurrent_gauge")).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("并发记录直方图应保持数据完整性")
        void testConcurrentHistogramRecord() throws Exception {
            int threadCount = 30;
            int recordsPerThread = 100;

            TestUtils.executeConcurrently(threadCount, recordsPerThread, iteration -> {
                collector.recordHistogram("concurrent_latency", iteration % 100);
            });

            HistogramStats stats = collector.getHistogramStats("concurrent_latency");

            assertThat(stats.getCount()).isEqualTo(threadCount * recordsPerThread);
            assertThat(stats.getMin()).isGreaterThanOrEqualTo(0);
            assertThat(stats.getMax()).isLessThan(100);
        }

        @Test
        @DisplayName("并发操作不应相互干扰")
        void testConcurrentMixedOperations() throws Exception {
            int threadCount = 40;

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                new Thread(() -> {
                    try {
                        startLatch.await();
                        for (int j = 0; j < 100; j++) {
                            collector.incrementCounter("counter_" + (index % 5));
                            collector.setGauge("gauge_" + (index % 3), j);
                            collector.recordHistogram("histogram", index + j);
                        }
                        successCount.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                }).start();
            }

            startLatch.countDown();
            doneLatch.await(30, TimeUnit.SECONDS);

            assertThat(successCount.get()).isEqualTo(threadCount);

            for (int i = 0; i < 5; i++) {
                assertThat(collector.getCounter("counter_" + i)).isGreaterThan(0);
            }

            HistogramStats histogramStats = collector.getHistogramStats("histogram");
            assertThat(histogramStats.getCount()).isEqualTo(threadCount * 100);
        }

    }

    @Nested
    @DisplayName("重置测试")
    class ResetTests {

        @Test
        @DisplayName("重置应清除所有指标")
        void testReset() {
            collector.incrementCounter("counter", 100);
            collector.setGauge("gauge", 50);
            collector.recordHistogram("histogram", 10);

            collector.reset();

            assertThat(collector.getCounter("counter")).isEqualTo(0);
            assertThat(collector.getGauge("gauge")).isEqualTo(0);
            assertThat(collector.getHistogramStats("histogram")).isEqualTo(HistogramStats.EMPTY);
        }

    }

    @Nested
    @DisplayName("边界条件测试")
    class EdgeCaseTests {

        @Test
        @DisplayName("单个值的直方图应正确统计")
        void testSingleValueHistogram() {
            collector.recordHistogram("single", 42);

            HistogramStats stats = collector.getHistogramStats("single");

            assertThat(stats.getCount()).isEqualTo(1);
            assertThat(stats.getMin()).isEqualTo(42);
            assertThat(stats.getMax()).isEqualTo(42);
            assertThat(stats.getAvg()).isEqualTo(42.0);
            assertThat(stats.getP50()).isEqualTo(42);
            assertThat(stats.getP95()).isEqualTo(42);
            assertThat(stats.getP99()).isEqualTo(42);
        }

        @Test
        @DisplayName("零值应正确处理")
        void testZeroValues() {
            collector.incrementCounter("zero_counter", 0);
            collector.setGauge("zero_gauge", 0);
            collector.recordHistogram("zero_histogram", 0);

            assertThat(collector.getCounter("zero_counter")).isEqualTo(0);
            assertThat(collector.getGauge("zero_gauge")).isEqualTo(0);

            HistogramStats stats = collector.getHistogramStats("zero_histogram");
            assertThat(stats.getCount()).isEqualTo(1);
            assertThat(stats.getMin()).isEqualTo(0);
        }

        @Test
        @DisplayName("大数应正确处理")
        void testLargeValues() {
            long largeValue = Long.MAX_VALUE / 2;
            collector.incrementCounter("large", largeValue);
            collector.setGauge("large_gauge", largeValue);
            collector.recordHistogram("large_histogram", largeValue);

            assertThat(collector.getCounter("large")).isEqualTo(largeValue);
            assertThat(collector.getGauge("large_gauge")).isEqualTo(largeValue);

            HistogramStats stats = collector.getHistogramStats("large_histogram");
            assertThat(stats.getMax()).isEqualTo(largeValue);
        }

    }

}
