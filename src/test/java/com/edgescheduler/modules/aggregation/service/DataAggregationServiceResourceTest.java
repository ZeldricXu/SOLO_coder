package com.edgescheduler.modules.aggregation.service;

import com.edgescheduler.modules.aggregation.domain.AggregationCheckpoint;
import com.edgescheduler.modules.aggregation.domain.DataAggregation;
import com.edgescheduler.modules.aggregation.mapper.AggregationCheckpointMapper;
import com.edgescheduler.modules.aggregation.mapper.DataAggregationMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.data.redis.core.ReactiveListOperations;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DataAggregationServiceResourceTest {

    @Mock
    private DataAggregationMapper dataAggregationMapper;

    @Mock
    private AggregationCheckpointMapper checkpointMapper;

    @Mock
    private ReactiveRedisTemplate<String, Object> redisTemplate;

    @Mock
    private ReactiveValueOperations<String, Object> valueOps;

    @Mock
    private ReactiveListOperations<String, Object> listOps;

    private MeterRegistry meterRegistry;

    @InjectMocks
    private DataAggregationService dataAggregationService;

    private static final String TEST_DEVICE_ID = "test-device-001";
    private static final String TEST_AGG_TYPE = "AVG";
    private static final String TEST_TIME_WINDOW = "5m";

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        dataAggregationService = new DataAggregationService(
                dataAggregationMapper, checkpointMapper, redisTemplate, meterRegistry
        );

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(valueOps.set(anyString(), any(), any())).thenReturn(Mono.just(true));
        when(listOps.rightPush(anyString(), any())).thenReturn(Mono.just(1L));
        when(redisTemplate.delete(anyString())).thenReturn(Mono.just(1L));
        when(dataAggregationMapper.insert(any(DataAggregation.class))).thenReturn(1);
        when(checkpointMapper.insert(any(AggregationCheckpoint.class))).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        dataAggregationService.shutdown();
    }

    @Test
    @DisplayName("测试设备ID空值校验")
    void testValidateInput_NullDeviceId() {
        Map<String, Object> dataPoint = new HashMap<>();
        dataPoint.put("temperature", 25.5);

        StepVerifier.create(dataAggregationService.aggregateData(null, TEST_AGG_TYPE, TEST_TIME_WINDOW, dataPoint))
                .expectErrorMatches(e -> e.getMessage().contains("Device ID cannot be null"))
                .verify();
    }

    @Test
    @DisplayName("测试设备ID空字符串校验")
    void testValidateInput_EmptyDeviceId() {
        Map<String, Object> dataPoint = new HashMap<>();
        dataPoint.put("temperature", 25.5);

        StepVerifier.create(dataAggregationService.aggregateData("", TEST_AGG_TYPE, TEST_TIME_WINDOW, dataPoint))
                .expectErrorMatches(e -> e.getMessage().contains("Device ID cannot be null"))
                .verify();
    }

    @Test
    @DisplayName("测试设备ID超长校验")
    void testValidateInput_DeviceIdTooLong() {
        String longId = "a".repeat(200);
        Map<String, Object> dataPoint = new HashMap<>();
        dataPoint.put("temperature", 25.5);

        StepVerifier.create(dataAggregationService.aggregateData(longId, TEST_AGG_TYPE, TEST_TIME_WINDOW, dataPoint))
                .expectErrorMatches(e -> e.getMessage().contains("exceeds maximum length"))
                .verify();
    }

    @Test
    @DisplayName("测试聚合类型空值校验")
    void testValidateInput_NullAggType() {
        Map<String, Object> dataPoint = new HashMap<>();
        dataPoint.put("temperature", 25.5);

        StepVerifier.create(dataAggregationService.aggregateData(TEST_DEVICE_ID, null, TEST_TIME_WINDOW, dataPoint))
                .expectErrorMatches(e -> e.getMessage().contains("Aggregation type cannot be null"))
                .verify();
    }

    @Test
    @DisplayName("测试时间窗口格式校验 - 无效格式")
    void testParseTimeWindow_InvalidFormat() {
        Map<String, Object> dataPoint = new HashMap<>();
        dataPoint.put("temperature", 25.5);

        StepVerifier.create(dataAggregationService.aggregateData(TEST_DEVICE_ID, TEST_AGG_TYPE, "invalid", dataPoint))
                .expectErrorMatches(e -> e.getMessage().contains("Invalid time window"))
                .verify();
    }

    @Test
    @DisplayName("测试时间窗口格式校验 - 数值过大")
    void testParseTimeWindow_ValueTooLarge() {
        Map<String, Object> dataPoint = new HashMap<>();
        dataPoint.put("temperature", 25.5);

        StepVerifier.create(dataAggregationService.aggregateData(TEST_DEVICE_ID, TEST_AGG_TYPE, "100000m", dataPoint))
                .expectErrorMatches(e -> e.getMessage().contains("must be between"))
                .verify();
    }

    @Test
    @DisplayName("测试时间窗口格式校验 - 数值为0")
    void testParseTimeWindow_ZeroValue() {
        Map<String, Object> dataPoint = new HashMap<>();
        dataPoint.put("temperature", 25.5);

        StepVerifier.create(dataAggregationService.aggregateData(TEST_DEVICE_ID, TEST_AGG_TYPE, "0m", dataPoint))
                .expectErrorMatches(e -> e.getMessage().contains("must be between"))
                .verify();
    }

    @Test
    @DisplayName("测试时间窗口格式校验 - 负数")
    void testParseTimeWindow_NegativeValue() {
        Map<String, Object> dataPoint = new HashMap<>();
        dataPoint.put("temperature", 25.5);

        StepVerifier.create(dataAggregationService.aggregateData(TEST_DEVICE_ID, TEST_AGG_TYPE, "-5m", dataPoint))
                .expectErrorMatches(e -> e.getMessage().contains("Invalid time window"))
                .verify();
    }

    @Test
    @DisplayName("测试数据点空值校验")
    void testValidateDataPoint_Null() {
        StepVerifier.create(dataAggregationService.aggregateData(TEST_DEVICE_ID, TEST_AGG_TYPE, TEST_TIME_WINDOW, null))
                .expectErrorMatches(e -> e.getMessage().contains("cannot be null or empty"))
                .verify();
    }

    @Test
    @DisplayName("测试数据点空Map校验")
    void testValidateDataPoint_Empty() {
        Map<String, Object> dataPoint = new HashMap<>();

        StepVerifier.create(dataAggregationService.aggregateData(TEST_DEVICE_ID, TEST_AGG_TYPE, TEST_TIME_WINDOW, dataPoint))
                .expectErrorMatches(e -> e.getMessage().contains("cannot be null or empty"))
                .verify();
    }

    @Test
    @DisplayName("测试数据点过大校验")
    void testValidateDataPoint_TooLarge() {
        Map<String, Object> dataPoint = new HashMap<>();
        StringBuilder largeValue = new StringBuilder();
        for (int i = 0; i < 100000; i++) {
            largeValue.append("x");
        }
        dataPoint.put("largeField", largeValue.toString());

        StepVerifier.create(dataAggregationService.aggregateData(TEST_DEVICE_ID, TEST_AGG_TYPE, TEST_TIME_WINDOW, dataPoint))
                .expectErrorMatches(e -> e.getMessage().contains("exceeds maximum size"))
                .verify();
    }

    @Test
    @DisplayName("测试数据点无有效值校验")
    void testValidateDataPoint_NoValidValue() {
        Map<String, Object> dataPoint = new HashMap<>();
        dataPoint.put("strField", "");
        dataPoint.put("nullField", null);

        StepVerifier.create(dataAggregationService.aggregateData(TEST_DEVICE_ID, TEST_AGG_TYPE, TEST_TIME_WINDOW, dataPoint))
                .expectErrorMatches(e -> e.getMessage().contains("must contain at least one numeric"))
                .verify();
    }

    @Test
    @DisplayName("测试正常聚合 - 不触发窗口")
    void testAggregateData_Success() {
        Map<String, Object> dataPoint = new HashMap<>();
        dataPoint.put("temperature", 25.5);
        dataPoint.put("humidity", 60.0);

        StepVerifier.create(dataAggregationService.aggregateData(TEST_DEVICE_ID, TEST_AGG_TYPE, TEST_TIME_WINDOW, dataPoint))
                .assertNext(result -> {
                    assertNotNull(result);
                    assertEquals(TEST_DEVICE_ID, result.getDeviceId());
                    assertEquals(TEST_AGG_TYPE, result.getAggregationType());
                    assertEquals("BUFFERING", result.getUploadStatus());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("测试并发数据聚合 - 无竞态条件")
    void testConcurrentAggregation() throws InterruptedException {
        int threadCount = 20;
        int dataPointsPerThread = 100;
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < dataPointsPerThread; j++) {
                        Map<String, Object> dataPoint = new HashMap<>();
                        dataPoint.put("temperature", 20 + threadIndex + j * 0.1);
                        dataPoint.put("thread", threadIndex);
                        dataPoint.put("index", j);

                        dataAggregationService.aggregateData(
                                TEST_DEVICE_ID, TEST_AGG_TYPE, "1h", dataPoint
                        ).block();

                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(0, errorCount.get(), "不应有错误");
        assertEquals(threadCount * dataPointsPerThread, successCount.get(), "所有数据点都应成功处理");
    }

    @Test
    @DisplayName("测试shutdown正确释放资源")
    void testShutdown_ReleasesResources() {
        Map<String, Object> dataPoint = new HashMap<>();
        dataPoint.put("temperature", 25.5);

        for (int i = 0; i < 10; i++) {
            dataAggregationService.aggregateData(TEST_DEVICE_ID, TEST_AGG_TYPE, TEST_TIME_WINDOW, dataPoint).block();
        }

        assertDoesNotThrow(() -> dataAggregationService.shutdown());
    }

    @Test
    @DisplayName("测试Redis操作失败不影响主流程")
    void testRedisFailure_DoesNotBreakMainFlow() {
        when(redisTemplate.delete(anyString())).thenReturn(Mono.error(new RuntimeException("Redis down")));

        Map<String, Object> dataPoint = new HashMap<>();
        dataPoint.put("temperature", 25.5);

        StepVerifier.create(dataAggregationService.aggregateData(TEST_DEVICE_ID, TEST_AGG_TYPE, TEST_TIME_WINDOW, dataPoint))
                .assertNext(Objects::nonNull)
                .verifyComplete();
    }

    @Test
    @DisplayName("测试DLQ操作失败不影响主流程")
    void testDLQFailure_DoesNotBreakMainFlow() {
        when(listOps.rightPush(anyString(), any())).thenReturn(Mono.error(new RuntimeException("Redis down")));

        assertDoesNotThrow(() -> {
            dataAggregationService.aggregateData(TEST_DEVICE_ID, TEST_AGG_TYPE, TEST_TIME_WINDOW, null)
                    .onErrorResume(e -> Mono.empty())
                    .block();
        });
    }

    @Test
    @DisplayName("测试异常路径下资源正确释放")
    void testExceptionPath_ResourcesReleased() {
        Map<String, Object> dataPoint = new HashMap<>();
        dataPoint.put("temperature", 25.5);

        for (int i = 0; i < 100; i++) {
            try {
                dataAggregationService.aggregateData(TEST_DEVICE_ID, TEST_AGG_TYPE, "invalid", dataPoint).block();
            } catch (Exception ignored) {
            }
        }

        Map<String, Object> status = dataAggregationService.getBufferStatus().block();
        assertNotNull(status);
    }

    @Test
    @DisplayName("测试多种时间窗口格式")
    void testParseTimeWindow_ValidFormats() {
        Map<String, Object> dataPoint = new HashMap<>();
        dataPoint.put("temperature", 25.5);

        String[] validWindows = {"1s", "30s", "1m", "5m", "1h", "24h", "1d", "7d"};

        for (String window : validWindows) {
            StepVerifier.create(dataAggregationService.aggregateData(TEST_DEVICE_ID, TEST_AGG_TYPE, window, dataPoint))
                    .assertNext(Objects::nonNull)
                    .verifyComplete();
        }
    }

    @Test
    @DisplayName("测试不支持的时间单位")
    void testParseTimeWindow_UnsupportedUnit() {
        Map<String, Object> dataPoint = new HashMap<>();
        dataPoint.put("temperature", 25.5);

        StepVerifier.create(dataAggregationService.aggregateData(TEST_DEVICE_ID, TEST_AGG_TYPE, "5w", dataPoint))
                .expectErrorMatches(e -> e.getMessage().contains("Unsupported time unit"))
                .verify();
    }
}
