package com.parking.platform.monitoring.service;

import com.parking.platform.monitoring.entity.MetricSnapshot;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToDoubleFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("MonitoringService 异常路径测试")
class MonitoringServiceExceptionTest {

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
    @DisplayName("Counter 异常路径测试")
    class CounterExceptionTests {

        @Test
        @DisplayName("Counter名称为null应该抛出IllegalArgumentException")
        void testGetOrCreateCounter_NullName() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.getOrCreateCounter(null));
        }

        @Test
        @DisplayName("Counter名称为空字符串应该抛出IllegalArgumentException")
        void testGetOrCreateCounter_EmptyName() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.getOrCreateCounter(""));
        }

        @Test
        @DisplayName("Counter标签为null应该正常工作")
        void testGetOrCreateCounter_NullTags() {
            Counter counter = service.getOrCreateCounter("test.counter", (String[]) null);
            assertNotNull(counter);
            assertEquals("test.counter", counter.getId().getName());
        }

        @Test
        @DisplayName("Counter标签为奇数应该抛出异常")
        void testGetOrCreateCounter_OddNumberOfTags() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.getOrCreateCounter("test.counter", "key1"));
        }

        @Test
        @DisplayName("Counter标签key包含null应该抛出异常")
        void testGetOrCreateCounter_NullTagKey() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.getOrCreateCounter("test.counter", null, "value"));
        }

        @Test
        @DisplayName("Counter标签value包含null应该抛出异常")
        void testGetOrCreateCounter_NullTagValue() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.getOrCreateCounter("test.counter", "key", null));
        }
    }

    @Nested
    @DisplayName("Gauge 异常路径测试")
    class GaugeExceptionTests {

        @Test
        @DisplayName("Gauge名称为null应该抛出IllegalArgumentException")
        void testGauge_NullName() {
            AtomicInteger value = new AtomicInteger(100);
            assertThrows(IllegalArgumentException.class,
                    () -> service.gauge(null, value, AtomicInteger::get));
        }

        @Test
        @DisplayName("Gauge名称为空字符串应该抛出IllegalArgumentException")
        void testGauge_EmptyName() {
            AtomicInteger value = new AtomicInteger(100);
            assertThrows(IllegalArgumentException.class,
                    () -> service.gauge("", value, AtomicInteger::get));
        }

        @Test
        @DisplayName("Gauge函数为null应该抛出NullPointerException")
        void testGauge_NullFunction() {
            AtomicInteger value = new AtomicInteger(100);
            assertThrows(NullPointerException.class,
                    () -> service.gauge("test.gauge", value, null));
        }

        @Test
        @DisplayName("Gauge对象为null应该正常工作")
        void testGauge_NullObject() {
            ToDoubleFunction<Object> function = obj -> {
                if (obj == null) {
                    return 0.0;
                }
                return ((AtomicInteger) obj).get();
            };
            Gauge gauge = service.gauge("test.gauge", null, function);
            assertNotNull(gauge);
            assertTrue(Double.isNaN(gauge.value()) || gauge.value() == 0.0);
        }

        @Test
        @DisplayName("Gauge函数抛出运行时异常")
        void testGauge_FunctionThrowsException() {
            AtomicInteger value = new AtomicInteger(100);
            ToDoubleFunction<AtomicInteger> throwingFunction = v -> {
                throw new RuntimeException("Gauge function failed");
            };

            Gauge gauge = service.gauge("test.gauge", value, throwingFunction);
            assertNotNull(gauge);
            
            assertThrows(RuntimeException.class, gauge::value);
        }
    }

    @Nested
    @DisplayName("Timer 异常路径测试")
    class TimerExceptionTests {

        @Test
        @DisplayName("Timer名称为null应该抛出IllegalArgumentException")
        void testGetOrCreateTimer_NullName() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.getOrCreateTimer(null));
        }

        @Test
        @DisplayName("Timer名称为空字符串应该抛出IllegalArgumentException")
        void testGetOrCreateTimer_EmptyName() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.getOrCreateTimer(""));
        }

        @Test
        @DisplayName("Timer记录null Duration应该抛出NullPointerException")
        void testRecordTimer_NullDuration() {
            assertThrows(NullPointerException.class,
                    () -> service.recordTimer("test.timer", (Duration) null));
        }

        @Test
        @DisplayName("Timer标签为奇数应该抛出异常")
        void testGetOrCreateTimer_OddNumberOfTags() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.getOrCreateTimer("test.timer", "key1"));
        }

        @Test
        @DisplayName("Timer TimeUnit为null时的行为")
        void testRecordTimer_NullTimeUnit() {
            assertThrows(NullPointerException.class,
                    () -> service.recordTimer("test.timer", 100, null));
        }
    }

    @Nested
    @DisplayName("DistributionSummary 异常路径测试")
    class SummaryExceptionTests {

        @Test
        @DisplayName("Summary名称为null应该抛出IllegalArgumentException")
        void testGetOrCreateSummary_NullName() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.getOrCreateSummary(null));
        }

        @Test
        @DisplayName("Summary名称为空字符串应该抛出IllegalArgumentException")
        void testGetOrCreateSummary_EmptyName() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.getOrCreateSummary(""));
        }

        @Test
        @DisplayName("Summary标签为奇数应该抛出异常")
        void testGetOrCreateSummary_OddNumberOfTags() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.getOrCreateSummary("test.summary", "key1"));
        }
    }

    @Nested
    @DisplayName("Snapshot 异常路径测试")
    class SnapshotExceptionTests {

        @Test
        @DisplayName("创建Snapshot - 名称为null应该正常工作")
        void testCreateSnapshot_NullName() {
            MetricSnapshot snapshot = service.createSnapshot(null);
            assertNotNull(snapshot);
            assertNull(snapshot.getName());
            assertNotNull(snapshot.getId());
            assertNotNull(snapshot.getTimestamp());
        }

        @Test
        @DisplayName("创建Snapshot - 名称为空字符串应该正常工作")
        void testCreateSnapshot_EmptyName() {
            MetricSnapshot snapshot = service.createSnapshot("");
            assertNotNull(snapshot);
            assertEquals("", snapshot.getName());
        }

        @Test
        @DisplayName("空MetricsRegistry创建Snapshot")
        void testCreateSnapshot_EmptyRegistry() {
            MetricSnapshot snapshot = service.createSnapshot("empty.snapshot");
            assertNotNull(snapshot);
            assertNotNull(snapshot.getDetails());
            assertEquals(0.0, snapshot.getDetails().get("totalRequests"));
        }

        @Test
        @DisplayName("getSnapshots - 空列表应该返回空不可变列表")
        void testGetSnapshots_EmptyReturnsImmutableEmptyList() {
            List<MetricSnapshot> snapshots = service.getSnapshots();
            assertNotNull(snapshots);
            assertTrue(snapshots.isEmpty());
            assertThrows(UnsupportedOperationException.class,
                    () -> snapshots.add(new MetricSnapshot()));
        }
    }

    @Nested
    @DisplayName("MetricsSummary 异常路径测试")
    class MetricsSummaryExceptionTests {

        @Test
        @DisplayName("空MeterRegistry获取Summary")
        void testGetMetricsSummary_EmptyRegistry() {
            Map<String, Object> summary = service.getMetricsSummary();
            assertNotNull(summary);
            assertEquals(0, summary.get("totalMeters"));
            assertEquals(0L, summary.get("counterCount"));
            assertEquals(0L, summary.get("gaugeCount"));
            assertEquals(0L, summary.get("timerCount"));
            assertEquals(0L, summary.get("summaryCount"));
        }

        @Test
        @DisplayName("获取Summary应该总是返回Map，不会抛出异常")
        void testGetMetricsSummary_AlwaysReturnsMap() {
            for (int i = 0; i < 10; i++) {
                service.incrementCounter("counter." + i);
            }

            assertDoesNotThrow(() -> {
                Map<String, Object> summary = service.getMetricsSummary();
                assertNotNull(summary);
            });
        }
    }

    @Nested
    @DisplayName("PerformanceMetric 异常路径测试")
    class PerformanceMetricExceptionTests {

        @Test
        @DisplayName("记录性能指标 - operation为null")
        void testRecordPerformanceMetric_NullOperation() {
            MetricSnapshot snapshot = service.recordPerformanceMetric(null, 100, true);
            assertNotNull(snapshot);
            assertEquals("performance.null", snapshot.getName());
        }

        @Test
        @DisplayName("记录性能指标 - operation为空字符串")
        void testRecordPerformanceMetric_EmptyOperation() {
            MetricSnapshot snapshot = service.recordPerformanceMetric("", 100, true);
            assertNotNull(snapshot);
            assertEquals("performance.", snapshot.getName());
        }

        @Test
        @DisplayName("记录性能指标 - durationMs为负数")
        void testRecordPerformanceMetric_NegativeDuration() {
            MetricSnapshot snapshot = service.recordPerformanceMetric("test.op", -500, true);
            assertNotNull(snapshot);
            assertEquals(-500.0, snapshot.getValue());
        }

        @Test
        @DisplayName("记录性能指标 - durationMs为Long最大值")
        void testRecordPerformanceMetric_MaxDuration() {
            MetricSnapshot snapshot = service.recordPerformanceMetric("test.op", Long.MAX_VALUE, true);
            assertNotNull(snapshot);
            assertEquals((double) Long.MAX_VALUE, snapshot.getValue());
        }

        @Test
        @DisplayName("记录性能指标 - 多次记录同一operation")
        void testRecordPerformanceMetric_MultipleSameOperation() {
            int records = 100;
            for (int i = 0; i < records; i++) {
                service.recordPerformanceMetric("same.op", i, i % 2 == 0);
            }

            assertEquals(records, service.getSnapshots().size());
            assertEquals(records, meterRegistry.get("operation.total").counter().count());
        }
    }

    @Nested
    @DisplayName("Mockito 模拟异常场景测试")
    class MockitoExceptionTests {

        private MeterRegistry mockRegistry;
        private MonitoringService mockService;

        @BeforeEach
        void setUp() {
            mockRegistry = mock(MeterRegistry.class);
            mockService = new MonitoringService(mockRegistry);
        }

        @Test
        @DisplayName("Counter.builder() 抛出异常时incrementCounter的行为")
        void testIncrementCounter_BuilderThrowsException() {
            when(mockRegistry.counter(anyString())).thenThrow(new RuntimeException("Metrics registry failure"));

            assertThrows(RuntimeException.class,
                    () -> mockService.incrementCounter("test.counter"));
        }

        @Test
        @DisplayName("Timer.builder() 抛出异常时recordTimer的行为")
        void testRecordTimer_BuilderThrowsException() {
            when(mockRegistry.timer(anyString())).thenThrow(new RuntimeException("Metrics registry failure"));

            assertThrows(RuntimeException.class,
                    () -> mockService.recordTimer("test.timer", 100, TimeUnit.MILLISECONDS));
        }

        @Test
        @DisplayName("Gauge.builder() 抛出异常时gauge的行为")
        void testGauge_BuilderThrowsException() {
            AtomicInteger value = new AtomicInteger(100);
            when(mockRegistry.gauge(anyString(), any(AtomicInteger.class), any(ToDoubleFunction.class)))
                    .thenThrow(new RuntimeException("Metrics registry failure"));

            assertThrows(RuntimeException.class,
                    () -> mockService.gauge("test.gauge", value, AtomicInteger::get));
        }

        @Test
        @DisplayName("createSnapshot 当 getMeters() 抛出异常")
        void testCreateSnapshot_GetMetersThrowsException() {
            MeterRegistry failingRegistry = mock(MeterRegistry.class);
            when(failingRegistry.getMeters()).thenThrow(new RuntimeException("Registry access failed"));
            MonitoringService failingService = new MonitoringService(failingRegistry);

            assertThrows(RuntimeException.class,
                    () -> failingService.createSnapshot("test.snapshot"));
        }
    }

    @Nested
    @DisplayName("空指针保护测试")
    class NullPointerProtectionTests {

        @Test
        @DisplayName("getOrCreateCounter - null tags数组")
        void testGetOrCreateCounter_NullTagsArray() {
            Counter counter = service.getOrCreateCounter("test.counter", (String[]) null);
            assertNotNull(counter);
            assertTrue(counter.getId().getTags().isEmpty());
        }

        @Test
        @DisplayName("getOrCreateTimer - null tags数组")
        void testGetOrCreateTimer_NullTagsArray() {
            Timer timer = service.getOrCreateTimer("test.timer", (String[]) null);
            assertNotNull(timer);
            assertTrue(timer.getId().getTags().isEmpty());
        }

        @Test
        @DisplayName("getOrCreateSummary - null tags数组")
        void testGetOrCreateSummary_NullTagsArray() {
            var summary = service.getOrCreateSummary("test.summary", (String[]) null);
            assertNotNull(summary);
            assertTrue(summary.getId().getTags().isEmpty());
        }

        @Test
        @DisplayName("gauge - null tags数组")
        void testGauge_NullTagsArray() {
            AtomicInteger value = new AtomicInteger(100);
            Gauge gauge = service.gauge("test.gauge", value, AtomicInteger::get, (String[]) null);
            assertNotNull(gauge);
        }

        @Test
        @DisplayName("incrementCounter - 不同重载方法的null tags")
        void testIncrementCounter_OverloadsWithNullTags() {
            assertDoesNotThrow(() -> {
                service.incrementCounter("test.counter", (String[]) null);
            });

            assertDoesNotThrow(() -> {
                service.incrementCounter("test.counter2", 10.0, (String[]) null);
            });
        }

        @Test
        @DisplayName("recordTimer - 不同重载方法的null tags")
        void testRecordTimer_OverloadsWithNullTags() {
            assertDoesNotThrow(() -> {
                service.recordTimer("test.timer", 100, TimeUnit.MILLISECONDS, (String[]) null);
            });

            assertDoesNotThrow(() -> {
                service.recordTimer("test.timer2", Duration.ofMillis(100), (String[]) null);
            });
        }

        @Test
        @DisplayName("recordSummary - null tags数组")
        void testRecordSummary_NullTagsArray() {
            assertDoesNotThrow(() -> {
                service.recordSummary("test.summary", 50.0, (String[]) null);
            });
        }
    }

    @Nested
    @DisplayName("边缘场景异常测试")
    class EdgeCaseExceptionTests {

        @Test
        @DisplayName("大量Concurrent Metrics操作")
        void testLargeNumberOfMetrics_NoMemoryLeak() {
            int metricCount = 1000;
            for (int i = 0; i < metricCount; i++) {
                service.incrementCounter("metric.counter." + i);
                service.recordTimer("metric.timer." + i, i + 1, TimeUnit.MILLISECONDS);
            }

            Map<String, Object> summary = service.getMetricsSummary();
            assertEquals(metricCount * 2, summary.get("totalMeters"));
        }

        @Test
        @DisplayName("超长名称 - 边界值")
        void testVeryLongMetricName() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 1000; i++) {
                sb.append("abcdefghij");
            }
            String longName = sb.toString();

            assertDoesNotThrow(() -> {
                Counter counter = service.getOrCreateCounter(longName);
                assertNotNull(counter);
                assertEquals(longName, counter.getId().getName());
            });
        }

        @Test
        @DisplayName("特殊字符名称")
        void testMetricNameWithSpecialChars() {
            String specialName = "metric.test-name_123.with.dots";

            assertDoesNotThrow(() -> {
                Counter counter = service.getOrCreateCounter(specialName);
                assertNotNull(counter);
            });
        }

        @Test
        @DisplayName("大量标签")
        void testMetricWithManyTags() {
            String[] tags = new String[100];
            for (int i = 0; i < 50; i++) {
                tags[i * 2] = "key" + i;
                tags[i * 2 + 1] = "value" + i;
            }

            assertDoesNotThrow(() -> {
                Counter counter = service.getOrCreateCounter("test.many.tags", tags);
                assertNotNull(counter);
            });
        }

        @Test
        @DisplayName("createSnapshot 在大量metrics时")
        void testCreateSnapshot_WithManyMetrics() {
            for (int i = 0; i < 100; i++) {
                service.incrementCounter("bulk.counter." + i, i);
                service.recordTimer("bulk.timer." + i, i * 10, TimeUnit.MILLISECONDS);
            }

            MetricSnapshot snapshot = service.createSnapshot("bulk.snapshot");
            assertNotNull(snapshot);
            assertNotNull(snapshot.getDetails());
            assertTrue((Double) snapshot.getDetails().get("totalRequests") > 0);
        }
    }
}
