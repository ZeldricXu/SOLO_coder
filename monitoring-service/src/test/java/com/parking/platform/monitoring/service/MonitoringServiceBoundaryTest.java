package com.parking.platform.monitoring.service;

import com.parking.platform.monitoring.entity.MetricSnapshot;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MonitoringService 边界条件测试")
class MonitoringServiceBoundaryTest {

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
    @DisplayName("Counter 边界条件测试")
    class CounterBoundaryTests {

        @Test
        @DisplayName("Counter名称为空字符串应该抛出异常")
        void testGetOrCreateCounter_EmptyName() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.getOrCreateCounter(""));
        }

        @Test
        @DisplayName("Counter名称为null应该抛出异常")
        void testGetOrCreateCounter_NullName() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.getOrCreateCounter(null));
        }

        @Test
        @DisplayName("Counter标签为null应该正常工作")
        void testGetOrCreateCounter_NullTags() {
            Counter counter = service.getOrCreateCounter("test.counter", (String[]) null);
            assertNotNull(counter);
            assertEquals("test.counter", counter.getId().getName());
        }

        @Test
        @DisplayName("Counter标签为空数组应该正常工作")
        void testGetOrCreateCounter_EmptyTags() {
            Counter counter = service.getOrCreateCounter("test.counter", new String[0]);
            assertNotNull(counter);
        }

        @Test
        @DisplayName("Counter标签数量为奇数应该抛出异常")
        void testGetOrCreateCounter_OddNumberOfTags() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.getOrCreateCounter("test.counter", "key1"));
        }

        @Test
        @DisplayName("Counter递增负数应该正常工作")
        void testIncrementCounter_NegativeAmount() {
            service.incrementCounter("test.counter", 10.0);
            assertEquals(10.0, meterRegistry.get("test.counter").counter().count());
            
            service.incrementCounter("test.counter", -5.0);
            assertEquals(5.0, meterRegistry.get("test.counter").counter().count());
        }

        @Test
        @DisplayName("Counter递增零值应该不改变计数值")
        void testIncrementCounter_ZeroAmount() {
            Counter counter = service.getOrCreateCounter("test.counter");
            double initial = counter.count();
            
            service.incrementCounter("test.counter", 0.0);
            
            assertEquals(initial, meterRegistry.get("test.counter").counter().count());
        }

        @Test
        @DisplayName("Counter递增最大值")
        void testIncrementCounter_MaxValue() {
            double maxValue = Double.MAX_VALUE;
            service.incrementCounter("test.counter", maxValue);
            
            assertTrue(meterRegistry.get("test.counter").counter().count() > 0);
        }

        @Test
        @DisplayName("多次递增同一个Counter")
        void testIncrementCounter_MultipleIncrements() {
            int iterations = 1000;
            for (int i = 0; i < iterations; i++) {
                service.incrementCounter("test.counter");
            }
            
            assertEquals(iterations, meterRegistry.get("test.counter").counter().count());
        }
    }

    @Nested
    @DisplayName("Gauge 边界条件测试")
    class GaugeBoundaryTests {

        @Test
        @DisplayName("Gauge名称为空字符串应该抛出异常")
        void testGauge_EmptyName() {
            AtomicInteger value = new AtomicInteger(0);
            assertThrows(IllegalArgumentException.class,
                    () -> service.gauge("", value, AtomicInteger::get));
        }

        @Test
        @DisplayName("Gauge对象为null应该正常返回")
        void testGauge_NullObject() {
            Gauge gauge = service.gauge("test.gauge", null, obj -> 0.0);
            assertNotNull(gauge);
            assertTrue(Double.isNaN(gauge.value()) || gauge.value() == 0.0);
        }

        @Test
        @DisplayName("Gauge函数为null应该抛出异常")
        void testGauge_NullFunction() {
            AtomicInteger value = new AtomicInteger(0);
            assertThrows(NullPointerException.class,
                    () -> service.gauge("test.gauge", value, null));
        }

        @Test
        @DisplayName("Gauge返回负值")
        void testGauge_NegativeValue() {
            AtomicInteger value = new AtomicInteger(-100);
            Gauge gauge = service.gauge("test.gauge", value, AtomicInteger::get);
            
            assertEquals(-100.0, gauge.value(), 0.001);
        }

        @Test
        @DisplayName("Gauge返回零值")
        void testGauge_ZeroValue() {
            AtomicInteger value = new AtomicInteger(0);
            Gauge gauge = service.gauge("test.gauge", value, AtomicInteger::get);
            
            assertEquals(0.0, gauge.value(), 0.001);
        }

        @Test
        @DisplayName("Gauge返回最大值")
        void testGauge_MaxValue() {
            AtomicInteger value = new AtomicInteger(Integer.MAX_VALUE);
            Gauge gauge = service.gauge("test.gauge", value, AtomicInteger::get);
            
            assertEquals(Integer.MAX_VALUE, gauge.value(), 0.001);
        }
    }

    @Nested
    @DisplayName("Timer 边界条件测试")
    class TimerBoundaryTests {

        @Test
        @DisplayName("Timer名称为空字符串应该抛出异常")
        void testGetOrCreateTimer_EmptyName() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.getOrCreateTimer(""));
        }

        @Test
        @DisplayName("Timer记录负值持续时间")
        void testRecordTimer_NegativeDuration() {
            service.recordTimer("test.timer", -100, TimeUnit.MILLISECONDS);
            
            Timer timer = meterRegistry.get("test.timer").timer();
            assertEquals(1, timer.count());
        }

        @Test
        @DisplayName("Timer记录零持续时间")
        void testRecordTimer_ZeroDuration() {
            service.recordTimer("test.timer", 0, TimeUnit.NANOSECONDS);
            
            Timer timer = meterRegistry.get("test.timer").timer();
            assertEquals(1, timer.count());
            assertEquals(0.0, timer.totalTime(TimeUnit.NANOSECONDS), 0.001);
        }

        @Test
        @DisplayName("Timer记录最大持续时间")
        void testRecordTimer_MaxDuration() {
            service.recordTimer("test.timer", Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            
            Timer timer = meterRegistry.get("test.timer").timer();
            assertEquals(1, timer.count());
            assertTrue(timer.totalTime(TimeUnit.NANOSECONDS) > 0);
        }

        @Test
        @DisplayName("Timer记录null Duration")
        void testRecordTimer_NullDuration() {
            assertThrows(NullPointerException.class,
                    () -> service.recordTimer("test.timer", (Duration) null));
        }

        @Test
        @DisplayName("Timer记录负Duration")
        void testRecordTimer_NegativeDurationObject() {
            service.recordTimer("test.timer", Duration.ofMillis(-100));
            
            Timer timer = meterRegistry.get("test.timer").timer();
            assertEquals(1, timer.count());
        }

        @Test
        @DisplayName("Timer记录零Duration")
        void testRecordTimer_ZeroDuration() {
            service.recordTimer("test.timer", Duration.ZERO);
            
            Timer timer = meterRegistry.get("test.timer").timer();
            assertEquals(1, timer.count());
        }

        @Test
        @DisplayName("Timer多次记录 - 统计正确")
        void testRecordTimer_MultipleRecords() {
            for (int i = 1; i <= 100; i++) {
                service.recordTimer("test.timer", i, TimeUnit.MILLISECONDS);
            }
            
            Timer timer = meterRegistry.get("test.timer").timer();
            assertEquals(100, timer.count());
            assertEquals(5050.0, timer.totalTime(TimeUnit.MILLISECONDS), 0.001);
        }
    }

    @Nested
    @DisplayName("Snapshot 边界条件测试")
    class SnapshotBoundaryTests {

        @Test
        @DisplayName("创建Snapshot - 名称为空字符串")
        void testCreateSnapshot_EmptyName() {
            MetricSnapshot snapshot = service.createSnapshot("");
            assertNotNull(snapshot);
            assertEquals("", snapshot.getName());
        }

        @Test
        @DisplayName("创建Snapshot - 名称为null")
        void testCreateSnapshot_NullName() {
            MetricSnapshot snapshot = service.createSnapshot(null);
            assertNotNull(snapshot);
            assertNull(snapshot.getName());
        }

        @Test
        @DisplayName("创建Snapshot - 名称超长")
        void testCreateSnapshot_VeryLongName() {
            String longName = "a".repeat(10000);
            MetricSnapshot snapshot = service.createSnapshot(longName);
            assertNotNull(snapshot);
            assertEquals(longName, snapshot.getName());
        }

        @Test
        @DisplayName("创建Snapshot - 没有任何metrics")
        void testCreateSnapshot_NoMetrics() {
            MetricSnapshot snapshot = service.createSnapshot("empty");
            
            assertNotNull(snapshot);
            assertNotNull(snapshot.getDetails());
            assertEquals(0.0, snapshot.getDetails().get("totalRequests"));
            assertEquals(MetricSnapshot.MetricType.GAUGE, snapshot.getType());
        }

        @Test
        @DisplayName("创建Snapshot - 只有Counters")
        void testCreateSnapshot_OnlyCounters() {
            service.incrementCounter("request.count", 100.0);
            service.incrementCounter("error.count", 5.0);
            
            MetricSnapshot snapshot = service.createSnapshot("counter-only");
            
            assertEquals(MetricSnapshot.MetricType.COUNTER, snapshot.getType());
            assertEquals(105.0, snapshot.getDetails().get("totalRequests"));
        }

        @Test
        @DisplayName("创建Snapshot - 只有Timers")
        void testCreateSnapshot_OnlyTimers() {
            service.recordTimer("request.duration", 50, TimeUnit.MILLISECONDS);
            service.recordTimer("db.query", 100, TimeUnit.MILLISECONDS);
            
            MetricSnapshot snapshot = service.createSnapshot("timer-only");
            
            assertEquals(MetricSnapshot.MetricType.TIMER, snapshot.getType());
            assertTrue(snapshot.getValue() > 0);
        }

        @Test
        @DisplayName("获取Snapshots - 空列表")
        void testGetSnapshots_Empty() {
            List<MetricSnapshot> snapshots = service.getSnapshots();
            assertTrue(snapshots.isEmpty());
        }

        @Test
        @DisplayName("获取Snapshots - 多个快照")
        void testGetSnapshots_Multiple() {
            int count = 10;
            for (int i = 0; i < count; i++) {
                service.createSnapshot("snap-" + i);
            }
            
            assertEquals(count, service.getSnapshots().size());
        }

        @Test
        @DisplayName("获取Snapshots - 返回的是不可变列表")
        void testGetSnapshots_ImmutableList() {
            service.createSnapshot("test");
            List<MetricSnapshot> snapshots = service.getSnapshots();
            
            assertThrows(UnsupportedOperationException.class,
                    () -> snapshots.add(new MetricSnapshot()));
        }
    }

    @Nested
    @DisplayName("Performance Metric 边界条件测试")
    class PerformanceMetricBoundaryTests {

        @Test
        @DisplayName("记录性能指标 - 操作为空字符串")
        void testRecordPerformanceMetric_EmptyOperation() {
            MetricSnapshot snapshot = service.recordPerformanceMetric("", 100, true);
            assertNotNull(snapshot);
            assertTrue(snapshot.getName().endsWith("."));
        }

        @Test
        @DisplayName("记录性能指标 - 操作为null")
        void testRecordPerformanceMetric_NullOperation() {
            MetricSnapshot snapshot = service.recordPerformanceMetric(null, 100, true);
            assertNotNull(snapshot);
        }

        @Test
        @DisplayName("记录性能指标 - 持续时间为负数")
        void testRecordPerformanceMetric_NegativeDuration() {
            MetricSnapshot snapshot = service.recordPerformanceMetric("test.op", -100, true);
            
            assertEquals(-100.0, snapshot.getValue());
            assertEquals("success", snapshot.getDimensions().get("status"));
        }

        @Test
        @DisplayName("记录性能指标 - 持续时间为零")
        void testRecordPerformanceMetric_ZeroDuration() {
            MetricSnapshot snapshot = service.recordPerformanceMetric("test.op", 0, true);
            
            assertEquals(0.0, snapshot.getValue());
        }

        @Test
        @DisplayName("记录性能指标 - 持续时间为最大值")
        void testRecordPerformanceMetric_MaxDuration() {
            MetricSnapshot snapshot = service.recordPerformanceMetric("test.op", Long.MAX_VALUE, true);
            
            assertEquals((double) Long.MAX_VALUE, snapshot.getValue());
        }

        @Test
        @DisplayName("记录性能指标 - success为true")
        void testRecordPerformanceMetric_Success() {
            MetricSnapshot snapshot = service.recordPerformanceMetric("test.op", 100, true);
            
            assertEquals("success", snapshot.getDimensions().get("status"));
            assertEquals(Boolean.TRUE, snapshot.getDetails().get("success"));
        }

        @Test
        @DisplayName("记录性能指标 - success为false")
        void testRecordPerformanceMetric_Failure() {
            MetricSnapshot snapshot = service.recordPerformanceMetric("test.op", 100, false);
            
            assertEquals("error", snapshot.getDimensions().get("status"));
            assertEquals(Boolean.FALSE, snapshot.getDetails().get("success"));
        }

        @Test
        @DisplayName("记录性能指标 - 详细信息包含所有字段")
        void testRecordPerformanceMetric_DetailsComplete() {
            MetricSnapshot snapshot = service.recordPerformanceMetric("test.op", 250, true);
            
            assertNotNull(snapshot.getId());
            assertNotNull(snapshot.getTimestamp());
            assertEquals("performance.test.op", snapshot.getName());
            assertEquals(250L, snapshot.getDetails().get("durationMs"));
            assertEquals("test.op", snapshot.getDimensions().get("operation"));
        }
    }

    @Nested
    @DisplayName("Metrics Summary 边界条件测试")
    class MetricsSummaryBoundaryTests {

        @Test
        @DisplayName("获取Metrics Summary - 没有任何metrics")
        void testGetMetricsSummary_Empty() {
            Map<String, Object> summary = service.getMetricsSummary();
            
            assertEquals(0, summary.get("totalMeters"));
            assertEquals(0L, summary.get("counterCount"));
            assertEquals(0L, summary.get("gaugeCount"));
            assertEquals(0L, summary.get("timerCount"));
            assertEquals(0L, summary.get("summaryCount"));
            assertTrue(((List<?>) summary.get("meters")).isEmpty());
        }

        @Test
        @DisplayName("获取Metrics Summary - 混合metrics")
        void testGetMetricsSummary_MixedMetrics() {
            service.incrementCounter("test.counter");
            service.incrementCounter("test.counter2");
            service.recordTimer("test.timer", 100, TimeUnit.MILLISECONDS);
            service.recordSummary("test.summary", 50.0);
            AtomicInteger gaugeValue = new AtomicInteger(100);
            service.gauge("test.gauge", gaugeValue, AtomicInteger::get);
            
            Map<String, Object> summary = service.getMetricsSummary();
            
            assertEquals(4, summary.get("totalMeters"));
            assertEquals(2L, summary.get("counterCount"));
            assertEquals(1L, summary.get("gaugeCount"));
            assertEquals(1L, summary.get("timerCount"));
            assertEquals(1L, summary.get("summaryCount"));
        }

        @Test
        @DisplayName("获取Metrics Summary - meters列表信息完整")
        void testGetMetricsSummary_MetersListComplete() {
            service.incrementCounter("test.counter", "env", "test", "region", "cn-east");
            
            Map<String, Object> summary = service.getMetricsSummary();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> meters = (List<Map<String, Object>>) summary.get("meters");
            
            assertEquals(1, meters.size());
            Map<String, Object> meterInfo = meters.get(0);
            assertEquals("test.counter", meterInfo.get("name"));
            assertEquals("COUNTER", meterInfo.get("type"));
            assertNotNull(meterInfo.get("tags"));
        }
    }

    @Nested
    @DisplayName("DistributionSummary 边界条件测试")
    class DistributionSummaryBoundaryTests {

        @Test
        @DisplayName("Summary名称为空字符串应该抛出异常")
        void testGetOrCreateSummary_EmptyName() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.getOrCreateSummary(""));
        }

        @Test
        @DisplayName("Summary记录负值")
        void testRecordSummary_NegativeValue() {
            service.recordSummary("test.summary", -50.0);
            
            var summary = meterRegistry.get("test.summary").summary();
            assertEquals(1, summary.count());
            assertEquals(-50.0, summary.totalAmount(), 0.001);
        }

        @Test
        @DisplayName("Summary记录零值")
        void testRecordSummary_ZeroValue() {
            service.recordSummary("test.summary", 0.0);
            
            var summary = meterRegistry.get("test.summary").summary();
            assertEquals(1, summary.count());
            assertEquals(0.0, summary.totalAmount(), 0.001);
        }

        @Test
        @DisplayName("Summary记录最大值")
        void testRecordSummary_MaxValue() {
            service.recordSummary("test.summary", Double.MAX_VALUE);
            
            var summary = meterRegistry.get("test.summary").summary();
            assertEquals(1, summary.count());
            assertTrue(summary.totalAmount() > 0);
        }

        @Test
        @DisplayName("Summary多次记录 - 统计正确")
        void testRecordSummary_MultipleRecords() {
            for (int i = 1; i <= 10; i++) {
                service.recordSummary("test.summary", i * 10.0);
            }
            
            var summary = meterRegistry.get("test.summary").summary();
            assertEquals(10, summary.count());
            assertEquals(550.0, summary.totalAmount(), 0.001);
            assertEquals(55.0, summary.mean(), 0.001);
        }
    }
}
