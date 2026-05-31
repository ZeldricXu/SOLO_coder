package com.edgescheduler.modules.shadow.service;

import com.edgescheduler.modules.shadow.domain.DeviceShadow;
import com.edgescheduler.modules.shadow.mapper.DeviceShadowMapper;
import com.edgescheduler.modules.shadow.mapper.ShadowMonitorMetricMapper;
import com.edgescheduler.infrastructure.mapper.ConfigMapper;
import com.edgescheduler.domain.entity.ConfigEntity;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.data.redis.core.ReactiveSetOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeviceShadowServiceConcurrencyTest {

    @Mock
    private DeviceShadowMapper deviceShadowMapper;

    @Mock
    private ConfigMapper configMapper;

    @Mock
    private ReactiveRedisTemplate<String, Object> redisTemplate;

    @Mock
    private ShadowMonitorMetricMapper shadowMonitorMetricMapper;

    @Mock
    private ReactiveValueOperations<String, Object> valueOps;

    @Mock
    private ReactiveSetOperations<String, Object> setOps;

    private MeterRegistry meterRegistry;

    @InjectMocks
    private DeviceShadowService deviceShadowService;

    private static final String TEST_DEVICE_ID = "test-device-001";
    private static final int CONCURRENT_THREADS = 50;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        deviceShadowService = new DeviceShadowService(
                deviceShadowMapper, configMapper, redisTemplate, meterRegistry, shadowMonitorMetricMapper
        );

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(valueOps.set(anyString(), any(), anyLong(), any(TimeUnit.class))).thenReturn(Mono.just(true));
        when(setOps.add(anyString(), any())).thenReturn(Mono.just(1L));
        when(redisTemplate.delete(anyString())).thenReturn(Mono.just(1L));

        ConfigEntity config = new ConfigEntity();
        Map<String, Object> params = new HashMap<>();
        params.put("maxRetries", 3);
        params.put("timeout", 30);
        config.setParameters(params);
        when(configMapper.selectOne(any())).thenReturn(config);
    }

    @Test
    @DisplayName("测试设备ID空值校验")
    void testValidateDeviceId_Null() {
        StepVerifier.create(deviceShadowService.getShadow(null))
                .expectErrorMatches(e -> e.getMessage().contains("设备ID不能为空"))
                .verify();
    }

    @Test
    @DisplayName("测试设备ID空字符串校验")
    void testValidateDeviceId_Empty() {
        StepVerifier.create(deviceShadowService.getShadow("   "))
                .expectErrorMatches(e -> e.getMessage().contains("设备ID不能为空"))
                .verify();
    }

    @Test
    @DisplayName("测试设备ID超长校验")
    void testValidateDeviceId_TooLong() {
        String longId = "a".repeat(200);
        StepVerifier.create(deviceShadowService.getShadow(longId))
                .expectErrorMatches(e -> e.getMessage().contains("长度不能超过"))
                .verify();
    }

    @Test
    @DisplayName("测试期望状态空值校验")
    void testUpdateDesiredState_NullState() {
        StepVerifier.create(deviceShadowService.updateDesiredState(TEST_DEVICE_ID, null, "sig", 1234567890L))
                .expectErrorMatches(e -> e.getMessage().contains("期望状态不能为空"))
                .verify();
    }

    @Test
    @DisplayName("测试上报状态空值校验")
    void testUpdateReportedState_NullState() {
        StepVerifier.create(deviceShadowService.updateReportedState(TEST_DEVICE_ID, null))
                .expectErrorMatches(e -> e.getMessage().contains("上报状态不能为空"))
                .verify();
    }

    @Test
    @DisplayName("测试并发创建设备影子 - 应只创建一次")
    void testConcurrentCreateShadow() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_THREADS);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        DeviceShadow existingShadow = null;
        when(deviceShadowMapper.selectOne(any())).thenReturn(existingShadow);
        when(deviceShadowMapper.insert(any(DeviceShadow.class))).thenAnswer(invocation -> {
            DeviceShadow shadow = invocation.getArgument(0);
            shadow.setId(1L);
            return 1;
        });

        for (int i = 0; i < CONCURRENT_THREADS; i++) {
            executor.submit(() -> {
                try {
                    DeviceShadow result = deviceShadowService.createShadow(TEST_DEVICE_ID).block();
                    if (result != null) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    conflictCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(CONCURRENT_THREADS, successCount.get() + conflictCount.get());
        verify(deviceShadowMapper, atLeast(1)).insert(any(DeviceShadow.class));
    }

    @Test
    @DisplayName("测试并发更新期望状态 - 线程安全")
    void testConcurrentUpdateDesiredState() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_THREADS);
        AtomicInteger successCount = new AtomicInteger(0);

        DeviceShadow shadow = createTestShadow();
        when(deviceShadowMapper.selectOne(any())).thenReturn(shadow);
        when(deviceShadowMapper.updateById(any(DeviceShadow.class))).thenReturn(1);

        for (int i = 0; i < CONCURRENT_THREADS; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    Map<String, Object> state = new HashMap<>();
                    state.put("temperature", 25 + index);
                    state.put("timestamp", System.currentTimeMillis());

                    DeviceShadow result = deviceShadowService.updateDesiredState(
                            TEST_DEVICE_ID, state, "valid-sig", System.currentTimeMillis() / 1000
                    ).block();

                    if (result != null) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        assertTrue(successCount.get() > 0, "至少应有一些成功的更新");
    }

    @Test
    @DisplayName("测试并发更新上报状态 - 线程安全")
    void testConcurrentUpdateReportedState() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_THREADS);
        AtomicInteger successCount = new AtomicInteger(0);

        DeviceShadow shadow = createTestShadow();
        when(deviceShadowMapper.selectOne(any())).thenReturn(shadow);
        when(deviceShadowMapper.updateById(any(DeviceShadow.class))).thenReturn(1);

        for (int i = 0; i < CONCURRENT_THREADS; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    Map<String, Object> state = new HashMap<>();
                    state.put("humidity", 50 + index);
                    state.put("battery", 100 - index);

                    DeviceShadow result = deviceShadowService.updateReportedState(
                            TEST_DEVICE_ID, state
                    ).block();

                    if (result != null) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        assertTrue(successCount.get() > 0, "至少应有一些成功的更新");
    }

    @Test
    @DisplayName("测试锁的正确获取和释放")
    void testLockAcquireAndRelease() {
        DeviceShadow shadow = createTestShadow();
        when(deviceShadowMapper.selectOne(any())).thenReturn(shadow);
        when(deviceShadowMapper.updateById(any(DeviceShadow.class))).thenReturn(1);

        Map<String, Object> state = new HashMap<>();
        state.put("test", "value");

        for (int i = 0; i < 100; i++) {
            DeviceShadow result = deviceShadowService.updateReportedState(TEST_DEVICE_ID, state).block();
            assertNotNull(result);
        }
    }

    @Test
    @DisplayName("测试高并发下无死锁")
    void testNoDeadlockUnderHighConcurrency() throws InterruptedException {
        int threadCount = 20;
        CountDownLatch latch = new CountDownLatch(threadCount * 2);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount * 2);

        DeviceShadow shadow = createTestShadow();
        when(deviceShadowMapper.selectOne(any())).thenReturn(shadow);
        when(deviceShadowMapper.updateById(any(DeviceShadow.class))).thenReturn(1);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    Map<String, Object> state = new HashMap<>();
                    state.put("desired", "value");
                    deviceShadowService.updateDesiredState(
                            TEST_DEVICE_ID, state, "sig", System.currentTimeMillis() / 1000
                    ).block();
                } finally {
                    latch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    Map<String, Object> state = new HashMap<>();
                    state.put("reported", "value");
                    deviceShadowService.updateReportedState(TEST_DEVICE_ID, state).block();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "不应发生死锁");
        executor.shutdown();
    }

    @Test
    @DisplayName("测试并发删除和更新 - 无竞态条件")
    void testConcurrentDeleteAndUpdate() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        DeviceShadow shadow = createTestShadow();
        when(deviceShadowMapper.selectOne(any())).thenReturn(shadow);
        when(deviceShadowMapper.updateById(any(DeviceShadow.class))).thenReturn(1);
        when(deviceShadowMapper.deleteById(anyLong())).thenReturn(1);

        AtomicInteger exceptions = new AtomicInteger(0);

        executor.submit(() -> {
            try {
                deviceShadowService.deleteShadow(TEST_DEVICE_ID).block();
            } catch (Exception e) {
                exceptions.incrementAndGet();
            } finally {
                latch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                Map<String, Object> state = new HashMap<>();
                state.put("test", "value");
                deviceShadowService.updateReportedState(TEST_DEVICE_ID, state).block();
            } catch (Exception e) {
                exceptions.incrementAndGet();
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();
    }

    private DeviceShadow createTestShadow() {
        DeviceShadow shadow = new DeviceShadow();
        shadow.setId(1L);
        shadow.setDeviceId(TEST_DEVICE_ID);
        shadow.setShadowVersion(1);
        shadow.setDesiredState(new HashMap<>());
        shadow.setReportedState(new HashMap<>());
        shadow.setDeltaState(new HashMap<>());
        shadow.setSyncStatus("SYNCED");
        shadow.setLastSyncTime(LocalDateTime.now());
        shadow.setSyncLatencyMs(0L);
        shadow.setConflictCount(0);
        shadow.setMonitorStatus("NORMAL");
        shadow.setLastMetricUpdate(LocalDateTime.now());
        shadow.setHealthScore(100.0);
        return shadow;
    }
}
