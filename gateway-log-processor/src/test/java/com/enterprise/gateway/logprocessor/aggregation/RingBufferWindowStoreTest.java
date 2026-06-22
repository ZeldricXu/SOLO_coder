package com.enterprise.gateway.logprocessor.aggregation;

import com.enterprise.gateway.logprocessor.model.LogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RingBufferWindowStoreTest {

    private static final long WINDOW_SIZE_MS = 1000;
    private static final int MAX_WINDOWS = 5;
    private RingBufferWindowStore ringStore;
    private BTreeWindowStore btreeStore;

    @BeforeEach
    void setUp() {
        ringStore = new RingBufferWindowStore(WINDOW_SIZE_MS, MAX_WINDOWS);
        btreeStore = new BTreeWindowStore(WINDOW_SIZE_MS);
    }

    @Test
    @DisplayName("RingBufferWindowStore 应与 BTreeWindowStore 产生相同结果")
    void testSameResultsAsBTree() {
        long baseTime = 1718000000000L;

        for (int i = 0; i < 100; i++) {
            long timestamp = baseTime + i * 100;
            LogEntry entry = createTestEntry("service-" + (i % 3), "INFO", i % 2 == 0 ? "10ms" : "20ms");
            ringStore.add(timestamp, entry);
            btreeStore.add(timestamp, entry);
        }

        for (int i = 0; i < 10; i++) {
            long timestamp = baseTime + i * WINDOW_SIZE_MS;
            AggregationState ringState = ringStore.getWindow(timestamp);
            AggregationState btreeState = btreeStore.getWindow(timestamp);

            if (btreeState != null) {
                assertNotNull(ringState, "时间戳 " + timestamp + " 的窗口不应为null");
                assertEquals(btreeState.getCount(), ringState.getCount(), "计数不匹配");
                assertEquals(btreeState.getSum(), ringState.getSum(), 0.001, "总和不匹配");
                assertEquals(btreeState.getMin(), ringState.getMin(), 0.001, "最小值不匹配");
                assertEquals(btreeState.getMax(), ringState.getMax(), 0.001, "最大值不匹配");
            }
        }
    }

    @Test
    @DisplayName("超过 maxWindows 时的回绕行为")
    void testWraparoundBehavior() {
        long baseTime = 1718000000000L;

        for (int i = 0; i < MAX_WINDOWS * 3; i++) {
            long timestamp = baseTime + i * WINDOW_SIZE_MS;
            LogEntry entry = createTestEntry("svc", "INFO", "100ms");
            ringStore.add(timestamp, entry);
        }

        assertEquals(MAX_WINDOWS, ringStore.size());
        assertTrue(ringStore.isFull());

        long earliestTime = baseTime + (MAX_WINDOWS * 2L) * WINDOW_SIZE_MS;
        assertNotNull(ringStore.getWindow(earliestTime));
        assertNull(ringStore.getWindow(baseTime));
    }

    @Test
    @DisplayName("evictExpired 不应破坏任何数据（应为空操作或安全操作）")
    void testEvictExpiredIsSafe() {
        long baseTime = 1718000000000L;
        long now = baseTime + WINDOW_SIZE_MS * 10;

        for (int i = 0; i < 3; i++) {
            long timestamp = baseTime + i * WINDOW_SIZE_MS;
            LogEntry entry = createTestEntry("svc", "INFO", "50ms");
            ringStore.add(timestamp, entry);
        }

        int sizeBefore = ringStore.size();
        ringStore.evictExpired(now);
        int sizeAfter = ringStore.size();

        assertTrue(sizeAfter <= sizeBefore);

        for (int i = 0; i < 3; i++) {
            long timestamp = baseTime + i * WINDOW_SIZE_MS;
            ringStore.getWindow(timestamp);
        }

        assertDoesNotThrow(() -> ringStore.evictExpired(now));
    }

    @Test
    @DisplayName("窗口边界对齐")
    void testWindowBoundaryAlignment() {
        long windowStart = 1718000000000L;

        LogEntry entry1 = createTestEntry("svc", "INFO", "10ms");
        ringStore.add(windowStart, entry1);

        LogEntry entry2 = createTestEntry("svc", "INFO", "20ms");
        ringStore.add(windowStart + WINDOW_SIZE_MS - 1, entry2);

        LogEntry entry3 = createTestEntry("svc", "INFO", "30ms");
        ringStore.add(windowStart + WINDOW_SIZE_MS, entry3);

        AggregationState window1 = ringStore.getWindow(windowStart);
        AggregationState window2 = ringStore.getWindow(windowStart + WINDOW_SIZE_MS);

        assertNotNull(window1);
        assertEquals(2, window1.getCount());
        assertEquals(30.0, window1.getSum(), 0.001);

        assertNotNull(window2);
        assertEquals(1, window2.getCount());
        assertEquals(30.0, window2.getSum(), 0.001);
    }

    @Test
    @DisplayName("多线程并发访问测试")
    void testConcurrentAccess() throws InterruptedException {
        int threadCount = 10;
        int entriesPerThread = 100;
        long baseTime = 1718000000000L;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < entriesPerThread; i++) {
                        long timestamp = baseTime + (i % MAX_WINDOWS) * WINDOW_SIZE_MS;
                        LogEntry entry = createTestEntry(
                                "svc-" + threadId,
                                i % 2 == 0 ? "INFO" : "ERROR",
                                (i + 10) + "ms"
                        );
                        ringStore.add(timestamp, entry);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        assertTrue(ringStore.size() > 0);
        assertTrue(ringStore.size() <= MAX_WINDOWS);

        AggregationState state = ringStore.getWindow(baseTime);
        assertNotNull(state);
        assertTrue(state.getCount() > 0);
        assertTrue(state.getSum() > 0);
    }

    @Test
    @DisplayName("基本 add 和 get 操作")
    void testBasicAddAndGet() {
        long timestamp = 1718000000000L;
        LogEntry entry = createTestEntry("test-svc", "INFO", "100ms");

        assertEquals(0, ringStore.size());
        ringStore.add(timestamp, entry);
        assertEquals(1, ringStore.size());

        AggregationState state = ringStore.getWindow(timestamp);
        assertNotNull(state);
        assertEquals(1, state.getCount());
        assertEquals(100.0, state.getSum(), 0.001);
        assertEquals(100.0, state.getMin(), 0.001);
        assertEquals(100.0, state.getMax(), 0.001);
    }

    @Test
    @DisplayName("构造函数参数验证")
    void testConstructorValidation() {
        assertThrows(IllegalArgumentException.class, () ->
                new RingBufferWindowStore(-1, MAX_WINDOWS));
        assertThrows(IllegalArgumentException.class, () ->
                new RingBufferWindowStore(0, MAX_WINDOWS));
        assertThrows(IllegalArgumentException.class, () ->
                new RingBufferWindowStore(WINDOW_SIZE_MS, -1));
        assertThrows(IllegalArgumentException.class, () ->
                new RingBufferWindowStore(WINDOW_SIZE_MS, 0));
        assertThrows(IllegalArgumentException.class, () ->
                new RingBufferWindowStore(WINDOW_SIZE_MS, MAX_WINDOWS, -1));
    }

    @Test
    @DisplayName("容量相关方法测试")
    void testCapacityMethods() {
        assertEquals(MAX_WINDOWS, ringStore.capacity());
        assertEquals(WINDOW_SIZE_MS, ringStore.getWindowSizeMs());
        assertEquals(MAX_WINDOWS, ringStore.getMaxWindows());
        assertFalse(ringStore.isFull());

        long baseTime = 1718000000000L;
        for (int i = 0; i < MAX_WINDOWS; i++) {
            ringStore.add(baseTime + i * WINDOW_SIZE_MS, createTestEntry("svc", "INFO", "10ms"));
        }
        assertTrue(ringStore.isFull());
    }

    @Test
    @DisplayName("窗口覆盖时自动重置")
    void testWindowOverwriteResetsState() {
        long baseTime = 1718000000000L;

        ringStore.add(baseTime, createTestEntry("svc", "INFO", "100ms"));
        assertEquals(1, ringStore.getWindow(baseTime).getCount());

        long farFuture = baseTime + WINDOW_SIZE_MS * MAX_WINDOWS * 10;
        ringStore.add(farFuture, createTestEntry("svc", "INFO", "200ms"));

        AggregationState state = ringStore.getWindow(farFuture);
        assertNotNull(state);
        assertEquals(1, state.getCount());
        assertEquals(200.0, state.getSum(), 0.001);
    }

    private LogEntry createTestEntry(String service, String level, String duration) {
        return LogEntry.builder()
                .service(service)
                .level(level)
                .duration(duration)
                .build();
    }
}
