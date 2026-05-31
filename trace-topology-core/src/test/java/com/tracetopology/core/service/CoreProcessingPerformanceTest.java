package com.tracetopology.core.service;

import com.tracetopology.api.service.CoreProcessingService;
import com.tracetopology.core.service.impl.CoreProcessingServiceImpl;
import com.tracetopology.spi.event.EventPublisher;
import com.tracetopology.spi.metrics.MetricsCollector;
import com.tracetopology.spi.repository.EntityRepository;
import com.tracetopology.spi.transaction.TransactionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CoreProcessingPerformanceTest {

    @Mock
    private EntityRepository entityRepository;

    @Mock
    private EventPublisher eventPublisher;

    @Mock
    private MetricsCollector metricsCollector;

    @Mock
    private TransactionManager transactionManager;

    private CoreProcessingServiceImpl coreProcessingService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        coreProcessingService = new CoreProcessingServiceImpl(
                entityRepository, eventPublisher, metricsCollector, transactionManager);

        when(entityRepository.findConfigParameters(any()))
                .thenReturn(Map.of(
                        "poolSize", 10,
                        "timeoutSeconds", 30,
                        "retries", 3
                ));

        when(transactionManager.executeInTransaction(any())).thenAnswer(invocation -> {
            java.util.function.Supplier<?> supplier = invocation.getArgument(0);
            return supplier.get();
        });
    }

    @AfterEach
    void tearDown() {
        coreProcessingService.shutdown();
    }

    @Test
    void testBatchProcessingPerformance() {
        int totalItems = 5000;
        List<Map<String, Object>> payloads = new ArrayList<>();
        for (int i = 0; i < totalItems; i++) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("index", i);
            payload.put("data", "test-data-" + i);
            payloads.add(payload);
        }

        Map<String, Object> params = new HashMap<>();
        params.put("batchSize", 1000);
        params.put("parallelism", 4);

        long startTime = System.currentTimeMillis();
        List<Map<String, Object>> results = coreProcessingService.processBatch(
                "trace_perf_001", "default", payloads, params);
        long duration = System.currentTimeMillis() - startTime;

        assertNotNull(results);
        assertEquals(totalItems, results.size());

        long successCount = results.stream()
                .filter(r -> !Boolean.TRUE.equals(r.get("failed")))
                .count();
        assertEquals(totalItems, successCount);

        double throughput = (double) totalItems / (duration / 1000.0);
        System.out.printf("批处理性能: 总数=%d, 耗时=%dms, 吞吐量=%.2f items/s%n",
                totalItems, duration, throughput);

        assertTrue(throughput > 100, "吞吐量应大于100 items/s, 实际: " + throughput);
    }

    @Test
    void testBatchProcessingWithDifferentBatchSizes() {
        int totalItems = 2000;
        List<Map<String, Object>> payloads = new ArrayList<>();
        for (int i = 0; i < totalItems; i++) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("index", i);
            payloads.add(payload);
        }

        int[] batchSizes = {100, 500, 1000};
        Map<Integer, Long> durations = new HashMap<>();

        for (int batchSize : batchSizes) {
            Map<String, Object> params = new HashMap<>();
            params.put("batchSize", batchSize);
            params.put("parallelism", 2);

            long startTime = System.currentTimeMillis();
            coreProcessingService.processBatch("trace_batch_" + batchSize, "default", payloads, params);
            long duration = System.currentTimeMillis() - startTime;
            durations.put(batchSize, duration);

            System.out.printf("批大小=%d, 耗时=%dms%n", batchSize, duration);
        }

        durations.forEach((size, time) -> assertTrue(time > 0, "批大小 " + size + " 耗时应大于0"));
    }

    @Test
    void testHotRuleCounter() {
        Map<String, Object> payload = Map.of("key", "value");
        Map<String, Object> params = Map.of(
                "requestId", "req_001",
                "timestamp", System.currentTimeMillis()
        );

        for (int i = 0; i < 100; i++) {
            coreProcessingService.process("trace_hot_" + i, "default", payload, params);
        }

        Map<String, Long> hotRules = coreProcessingService.getHotRules();
        assertNotNull(hotRules);
    }

    @Test
    void testEmptyBatchProcessing() {
        List<Map<String, Object>> emptyPayloads = new ArrayList<>();
        Map<String, Object> params = Map.of(
                "requestId", "req_empty",
                "timestamp", System.currentTimeMillis()
        );

        List<Map<String, Object>> results = coreProcessingService.processBatch(
                "trace_empty_001", "default", emptyPayloads, params);

        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    void testRuleOptimizer() {
        coreProcessingService.registerRuleOptimizer("fastRule", payload -> "optimized-" + payload.get("key"));

        Map<String, Object> payload = Map.of("key", "test");
        Map<String, Object> params = Map.of(
                "requestId", "req_opt",
                "timestamp", System.currentTimeMillis()
        );

        Map<String, Object> result = coreProcessingService.process(
                "trace_opt_001", "default", payload, params);

        assertNotNull(result);
        assertTrue((Boolean) result.get("processed"));
    }

    @Test
    void testSingleItemProcessing() {
        Map<String, Object> payload = Map.of("key", "single-test");
        Map<String, Object> params = Map.of(
                "requestId", "req_single",
                "timestamp", System.currentTimeMillis()
        );

        long startTime = System.currentTimeMillis();
        Map<String, Object> result = coreProcessingService.process(
                "trace_single_001", "default", payload, params);
        long duration = System.currentTimeMillis() - startTime;

        assertNotNull(result);
        assertTrue((Boolean) result.get("processed"));
        assertTrue(duration < 1000, "单次处理应在1秒内完成, 实际: " + duration + "ms");
    }
}
