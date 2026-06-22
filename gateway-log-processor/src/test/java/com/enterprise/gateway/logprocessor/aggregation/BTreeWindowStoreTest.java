package com.enterprise.gateway.logprocessor.aggregation;

import com.enterprise.gateway.logprocessor.model.LogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BTreeWindowStoreTest {

    private static final long WINDOW_SIZE_MS = 1000;
    private static final long RETENTION_MS = 10000;
    private BTreeWindowStore store;

    @BeforeEach
    void setUp() {
        store = new BTreeWindowStore(WINDOW_SIZE_MS, RETENTION_MS);
    }

    @Test
    @DisplayName("基本 add 和 get 操作")
    void testBasicAddAndGet() {
        long timestamp = 1718000000000L;
        LogEntry entry = createTestEntry("svc", "INFO", "100ms");

        assertEquals(0, store.size());
        store.add(timestamp, entry);
        assertEquals(1, store.size());

        AggregationState state = store.getWindow(timestamp);
        assertNotNull(state);
        assertEquals(1, state.getCount());
        assertEquals(100.0, state.getSum(), 0.001);
        assertEquals(100.0, state.getMin(), 0.001);
        assertEquals(100.0, state.getMax(), 0.001);
    }

    @Test
    @DisplayName("evictExpired 应移除过期窗口")
    void testEvictExpiredRemovesOldWindows() {
        long baseTime = 1718000000000L;

        for (int i = 0; i < 20; i++) {
            long timestamp = baseTime + i * WINDOW_SIZE_MS;
            store.add(timestamp, createTestEntry("svc", "INFO", "10ms"));
        }

        assertEquals(20, store.size());

        long now = baseTime + 15 * WINDOW_SIZE_MS;
        store.evictExpired(now);

        int expectedRemaining = 10;
        assertTrue(store.size() <= expectedRemaining,
                "过期窗口应被移除，剩余: " + store.size() + "，预期最多: " + expectedRemaining);

        long cutoff = now - RETENTION_MS;
        assertTrue(store.getEarliestWindowStart() >= cutoff,
                "最早的窗口应在保留期内");
    }

    @Test
    @DisplayName("聚合计算正确性")
    void testAggregationCalculations() {
        long windowStart = 1718000000000L;

        String[] durations = {"10ms", "20ms", "30ms", "40ms", "50ms"};
        for (String duration : durations) {
            store.add(windowStart, createTestEntry("svc", "INFO", duration));
        }

        AggregationState state = store.getWindow(windowStart);
        assertNotNull(state);

        assertEquals(5, state.getCount());
        assertEquals(150.0, state.getSum(), 0.001);
        assertEquals(10.0, state.getMin(), 0.001);
        assertEquals(50.0, state.getMax(), 0.001);
        assertEquals(30.0, state.getAverage(), 0.001);

        double expectedVariance = (100 + 400 + 900 + 1600 + 2500) / 5.0 - 900;
        assertEquals(expectedVariance, state.getVariance(), 0.001);
        assertEquals(Math.sqrt(expectedVariance), state.getStandardDeviation(), 0.001);
    }

    @Test
    @DisplayName("多个窗口的聚合")
    void testMultipleWindows() {
        long baseTime = 1718000000000L;

        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 3; j++) {
                long timestamp = baseTime + i * WINDOW_SIZE_MS + j * 100;
                store.add(timestamp, createTestEntry("svc", "INFO", (j + 1) * 10 + "ms"));
            }
        }

        assertEquals(5, store.size());
        assertEquals(baseTime, store.getEarliestWindowStart());
        assertEquals(baseTime + 4 * WINDOW_SIZE_MS, store.getLatestWindowStart());

        for (int i = 0; i < 5; i++) {
            long timestamp = baseTime + i * WINDOW_SIZE_MS;
            AggregationState state = store.getWindow(timestamp);
            assertNotNull(state);
            assertEquals(3, state.getCount());
            assertEquals(60.0, state.getSum(), 0.001);
        }
    }

    @Test
    @DisplayName("空窗口统计")
    void testEmptyWindowStatistics() {
        AggregationState emptyState = new AggregationState();
        assertEquals(0, emptyState.getCount());
        assertEquals(0.0, emptyState.getAverage(), 0.001);
        assertEquals(0.0, emptyState.getVariance(), 0.001);
        assertEquals(0.0, emptyState.getStandardDeviation(), 0.001);
    }

    @Test
    @DisplayName("单个数据点的方差应为0")
    void testSingleDataPointVariance() {
        long timestamp = 1718000000000L;
        store.add(timestamp, createTestEntry("svc", "INFO", "42ms"));

        AggregationState state = store.getWindow(timestamp);
        assertNotNull(state);
        assertEquals(1, state.getCount());
        assertEquals(0.0, state.getVariance(), 0.001);
        assertEquals(0.0, state.getStandardDeviation(), 0.001);
    }

    @Test
    @DisplayName("构造函数参数验证")
    void testConstructorValidation() {
        assertThrows(IllegalArgumentException.class, () ->
                new BTreeWindowStore(-1));
        assertThrows(IllegalArgumentException.class, () ->
                new BTreeWindowStore(0));
        assertThrows(IllegalArgumentException.class, () ->
                new BTreeWindowStore(WINDOW_SIZE_MS, -1));
        assertThrows(IllegalArgumentException.class, () ->
                new BTreeWindowStore(WINDOW_SIZE_MS, 0));
    }

    @Test
    @DisplayName("getter 方法测试")
    void testGetterMethods() {
        assertEquals(WINDOW_SIZE_MS, store.getWindowSizeMs());
        assertEquals(RETENTION_MS, store.getRetentionMs());
        assertEquals(-1, store.getEarliestWindowStart());
        assertEquals(-1, store.getLatestWindowStart());

        long timestamp = 1718000000000L;
        store.add(timestamp, createTestEntry("svc", "INFO", "10ms"));

        assertEquals(timestamp, store.getEarliestWindowStart());
        assertEquals(timestamp, store.getLatestWindowStart());
    }

    @Test
    @DisplayName("AggregationState reset 方法")
    void testAggregationStateReset() {
        AggregationState state = new AggregationState();
        state.merge(createTestEntry("svc", "INFO", "100ms"));
        state.merge(createTestEntry("svc", "INFO", "200ms"));

        assertEquals(2, state.getCount());
        assertEquals(300.0, state.getSum(), 0.001);

        state.reset();

        assertEquals(0, state.getCount());
        assertEquals(0.0, state.getSum(), 0.001);
        assertEquals(Double.POSITIVE_INFINITY, state.getMin());
        assertEquals(Double.NEGATIVE_INFINITY, state.getMax());
        assertEquals(0.0, state.getSumOfSquares(), 0.001);
    }

    @Test
    @DisplayName("AggregationState combine 方法")
    void testAggregationStateCombine() {
        AggregationState state1 = new AggregationState();
        state1.merge(createTestEntry("svc", "INFO", "10ms"));
        state1.merge(createTestEntry("svc", "INFO", "30ms"));

        AggregationState state2 = new AggregationState();
        state2.merge(createTestEntry("svc", "INFO", "20ms"));
        state2.merge(createTestEntry("svc", "INFO", "40ms"));

        state1.combine(state2);

        assertEquals(4, state1.getCount());
        assertEquals(100.0, state1.getSum(), 0.001);
        assertEquals(10.0, state1.getMin(), 0.001);
        assertEquals(40.0, state1.getMax(), 0.001);

        AggregationState emptyState = new AggregationState();
        state1.combine(emptyState);
        assertEquals(4, state1.getCount());

        state1.combine(null);
        assertEquals(4, state1.getCount());
    }

    @Test
    @DisplayName("空数据和null duration 处理")
    void testNullAndEmptyDuration() {
        long timestamp = 1718000000000L;
        store.add(timestamp, createTestEntry("svc", "INFO", null));
        store.add(timestamp + 100, createTestEntry("svc", "INFO", ""));
        store.add(timestamp + 200, createTestEntry("svc", "INFO", "invalid"));

        AggregationState state = store.getWindow(timestamp);
        assertNotNull(state);
        assertEquals(3, state.getCount());
        assertEquals(0.0, state.getSum(), 0.001);
    }

    private LogEntry createTestEntry(String service, String level, String duration) {
        return LogEntry.builder()
                .service(service)
                .level(level)
                .duration(duration)
                .build();
    }
}
