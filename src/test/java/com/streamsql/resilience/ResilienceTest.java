package com.streamsql.resilience;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("容错策略测试")
class ResilienceTest {

    @Mock
    private ResilienceConfig resilienceConfig;

    @InjectMocks
    private CircuitBreakerManager circuitBreakerManager;

    @InjectMocks
    private ResilienceService resilienceService;

    @Nested
    @DisplayName("熔断器测试")
    class CircuitBreakerTest {

        @Test
        @DisplayName("熔断器初始状态为CLOSED")
        void shouldBeClosedInitially() {
            CircuitBreaker circuitBreaker = new CircuitBreaker("test", new ResilienceConfig.CircuitBreakerConfig());

            assertEquals(ResilienceConfig.CircuitBreakerState.CLOSED, circuitBreaker.getState());
        }

        @Test
        @DisplayName("CLOSED状态允许调用")
        void shouldPermitCallWhenClosed() {
            CircuitBreaker circuitBreaker = new CircuitBreaker("test", new ResilienceConfig.CircuitBreakerConfig());

            assertTrue(circuitBreaker.isCallPermitted());
        }

        @Test
        @DisplayName("成功调用重置失败计数")
        void shouldResetFailureCountOnSuccess() {
            ResilienceConfig.CircuitBreakerConfig config = new ResilienceConfig.CircuitBreakerConfig();
            config.setFailureThreshold(5);
            CircuitBreaker circuitBreaker = new CircuitBreaker("test", config);

            circuitBreaker.recordFailure(new RuntimeException("test"));
            assertEquals(1, circuitBreaker.getFailureCount());

            circuitBreaker.recordSuccess();
            assertEquals(0, circuitBreaker.getFailureCount());
        }

        @Test
        @DisplayName("达到失败阈值后熔断器打开")
        void shouldOpenCircuitAfterFailureThreshold() {
            ResilienceConfig.CircuitBreakerConfig config = new ResilienceConfig.CircuitBreakerConfig();
            config.setFailureThreshold(3);
            CircuitBreaker circuitBreaker = new CircuitBreaker("test", config);

            circuitBreaker.recordFailure(new RuntimeException("1"));
            circuitBreaker.recordFailure(new RuntimeException("2"));
            circuitBreaker.recordFailure(new RuntimeException("3"));

            assertEquals(ResilienceConfig.CircuitBreakerState.OPEN, circuitBreaker.getState());
        }

        @Test
        @DisplayName("OPEN状态不允许调用")
        void shouldNotPermitCallWhenOpen() {
            ResilienceConfig.CircuitBreakerConfig config = new ResilienceConfig.CircuitBreakerConfig();
            config.setFailureThreshold(1);
            config.setHalfOpenWaitMs(10000);
            CircuitBreaker circuitBreaker = new CircuitBreaker("test", config);

            circuitBreaker.recordFailure(new RuntimeException("test"));

            assertFalse(circuitBreaker.isCallPermitted());
        }

        @Test
        @DisplayName("HALF_OPEN状态成功调用后转为CLOSED")
        void shouldTransitionToClosedAfterSuccessInHalfOpen() throws InterruptedException {
            ResilienceConfig.CircuitBreakerConfig config = new ResilienceConfig.CircuitBreakerConfig();
            config.setFailureThreshold(1);
            config.setSuccessThreshold(1);
            config.setHalfOpenWaitMs(1);
            CircuitBreaker circuitBreaker = new CircuitBreaker("test", config);

            circuitBreaker.recordFailure(new RuntimeException("test"));
            assertEquals(ResilienceConfig.CircuitBreakerState.OPEN, circuitBreaker.getState());

            Thread.sleep(10);
            circuitBreaker.isCallPermitted();
            assertEquals(ResilienceConfig.CircuitBreakerState.HALF_OPEN, circuitBreaker.getState());

            circuitBreaker.recordSuccess();
            assertEquals(ResilienceConfig.CircuitBreakerState.CLOSED, circuitBreaker.getState());
        }

        @Test
        @DisplayName("HALF_OPEN状态失败调用后转为OPEN")
        void shouldTransitionToOpenAfterFailureInHalfOpen() throws InterruptedException {
            ResilienceConfig.CircuitBreakerConfig config = new ResilienceConfig.CircuitBreakerConfig();
            config.setFailureThreshold(1);
            config.setHalfOpenWaitMs(1);
            CircuitBreaker circuitBreaker = new CircuitBreaker("test", config);

            circuitBreaker.recordFailure(new RuntimeException("test"));
            assertEquals(ResilienceConfig.CircuitBreakerState.OPEN, circuitBreaker.getState());

            Thread.sleep(10);
            circuitBreaker.isCallPermitted();
            assertEquals(ResilienceConfig.CircuitBreakerState.HALF_OPEN, circuitBreaker.getState());

            circuitBreaker.recordFailure(new RuntimeException("test"));
            assertEquals(ResilienceConfig.CircuitBreakerState.OPEN, circuitBreaker.getState());
        }

        @Test
        @DisplayName("重置熔断器")
        void shouldResetCircuitBreaker() {
            ResilienceConfig.CircuitBreakerConfig config = new ResilienceConfig.CircuitBreakerConfig();
            config.setFailureThreshold(3);
            CircuitBreaker circuitBreaker = new CircuitBreaker("test", config);

            circuitBreaker.recordFailure(new RuntimeException("test"));
            circuitBreaker.reset();

            assertEquals(ResilienceConfig.CircuitBreakerState.CLOSED, circuitBreaker.getState());
            assertEquals(0, circuitBreaker.getFailureCount());
        }

        @Test
        @DisplayName("获取熔断器指标")
        void shouldGetCircuitBreakerMetrics() {
            ResilienceConfig.CircuitBreakerConfig config = new ResilienceConfig.CircuitBreakerConfig();
            CircuitBreaker circuitBreaker = new CircuitBreaker("test", config);

            CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();

            assertNotNull(metrics);
            assertEquals("test", metrics.name());
            assertEquals(ResilienceConfig.CircuitBreakerState.CLOSED, metrics.state());
            assertEquals(0, metrics.failureCount());
            assertEquals(0, metrics.successCount());
        }
    }

    @Nested
    @DisplayName("熔断器管理器测试")
    class CircuitBreakerManagerTest {

        @Test
        @DisplayName("获取熔断器 - 不存在则创建")
        void shouldCreateCircuitBreakerIfNotExists() {
            CircuitBreaker circuitBreaker = circuitBreakerManager.getCircuitBreaker("test");

            assertNotNull(circuitBreaker);
            assertEquals("test", circuitBreaker.getName());
        }

        @Test
        @DisplayName("获取熔断器 - 已存在则返回")
        void shouldReturnExistingCircuitBreaker() {
            CircuitBreaker cb1 = circuitBreakerManager.getCircuitBreaker("test");
            CircuitBreaker cb2 = circuitBreakerManager.getCircuitBreaker("test");

            assertSame(cb1, cb2);
        }

        @Test
        @DisplayName("执行带熔断器保护的操作 - 成功")
        void shouldExecuteOperationWithCircuitBreakerSuccess() {
            String result = circuitBreakerManager.execute(
                    "test",
                    () -> "success",
                    () -> "fallback"
            );

            assertEquals("success", result);
        }

        @Test
        @DisplayName("执行带熔断器保护的操作 - 失败走降级")
        void shouldExecuteFallbackOnFailure() {
            String result = circuitBreakerManager.execute(
                    "test",
                    () -> {
                        throw new RuntimeException("operation failed");
                    },
                    () -> "fallback"
            );

            assertEquals("fallback", result);
        }

        @Test
        @DisplayName("执行Runnable操作 - 成功")
        void shouldExecuteRunnableWithCircuitBreakerSuccess() {
            AtomicInteger counter = new AtomicInteger(0);

            circuitBreakerManager.execute(
                    "test",
                    counter::incrementAndGet,
                    () -> counter.set(-1)
            );

            assertEquals(1, counter.get());
        }

        @Test
        @DisplayName("执行Runnable操作 - 失败走降级")
        void shouldExecuteRunnableFallbackOnFailure() {
            AtomicInteger counter = new AtomicInteger(0);

            circuitBreakerManager.execute(
                    "test",
                    () -> {
                        throw new RuntimeException("operation failed");
                    },
                    () -> counter.set(-1)
            );

            assertEquals(-1, counter.get());
        }

        @Test
        @DisplayName("带重试执行操作 - 成功")
        void shouldExecuteWithRetrySuccess() {
            when(resilienceConfig.getRetry()).thenReturn(new ResilienceConfig.RetryConfig());
            when(resilienceConfig.getRetry().getMaxAttempts()).thenReturn(3);
            when(resilienceConfig.getRetry().getInitialDelayMs()).thenReturn(1L);
            when(resilienceConfig.getRetry().isEnableExponentialBackoff()).thenReturn(false);
            when(resilienceConfig.getRetry().getMultiplier()).thenReturn(1.0);
            when(resilienceConfig.getRetry().getMaxDelayMs()).thenReturn(100L);

            AtomicInteger attempts = new AtomicInteger(0);

            String result = circuitBreakerManager.executeWithRetry(
                    "test",
                    () -> {
                        if (attempts.incrementAndGet() < 2) {
                            throw new RuntimeException("temporary failure");
                        }
                        return "success";
                    },
                    () -> "fallback"
            );

            assertEquals("success", result);
            assertEquals(2, attempts.get());
        }

        @Test
        @DisplayName("带重试执行操作 - 全部失败走降级")
        void shouldExecuteFallbackAfterAllRetriesFailed() {
            when(resilienceConfig.getRetry()).thenReturn(new ResilienceConfig.RetryConfig());
            when(resilienceConfig.getRetry().getMaxAttempts()).thenReturn(3);
            when(resilienceConfig.getRetry().getInitialDelayMs()).thenReturn(1L);
            when(resilienceConfig.getRetry().isEnableExponentialBackoff()).thenReturn(false);
            when(resilienceConfig.getRetry().getMultiplier()).thenReturn(1.0);
            when(resilienceConfig.getRetry().getMaxDelayMs()).thenReturn(100L);

            AtomicInteger attempts = new AtomicInteger(0);

            String result = circuitBreakerManager.executeWithRetry(
                    "test",
                    () -> {
                        attempts.incrementAndGet();
                        throw new RuntimeException("persistent failure");
                    },
                    () -> "fallback"
            );

            assertEquals("fallback", result);
            assertEquals(3, attempts.get());
        }

        @Test
        @DisplayName("重置单个熔断器")
        void shouldResetSingleCircuitBreaker() {
            CircuitBreaker circuitBreaker = circuitBreakerManager.getCircuitBreaker("test");
            circuitBreaker.recordFailure(new RuntimeException("test"));

            circuitBreakerManager.resetCircuitBreaker("test");

            assertEquals(ResilienceConfig.CircuitBreakerState.CLOSED, circuitBreaker.getState());
        }

        @Test
        @DisplayName("重置所有熔断器")
        void shouldResetAllCircuitBreakers() {
            CircuitBreaker cb1 = circuitBreakerManager.getCircuitBreaker("test1");
            CircuitBreaker cb2 = circuitBreakerManager.getCircuitBreaker("test2");
            cb1.recordFailure(new RuntimeException("test"));
            cb2.recordFailure(new RuntimeException("test"));

            circuitBreakerManager.resetAll();

            assertEquals(ResilienceConfig.CircuitBreakerState.CLOSED, cb1.getState());
            assertEquals(ResilienceConfig.CircuitBreakerState.CLOSED, cb2.getState());
        }

        @Test
        @DisplayName("获取所有熔断器指标")
        void shouldGetAllCircuitBreakerMetrics() {
            circuitBreakerManager.getCircuitBreaker("test1");
            circuitBreakerManager.getCircuitBreaker("test2");

            var metrics = circuitBreakerManager.getAllMetrics();

            assertNotNull(metrics);
            assertTrue(metrics.containsKey("test1"));
            assertTrue(metrics.containsKey("test2"));
        }
    }

    @Nested
    @DisplayName("容错服务测试")
    class ResilienceServiceTest {

        @Test
        @DisplayName("带熔断器执行 - 成功")
        void shouldExecuteWithCircuitBreaker() {
            String result = resilienceService.executeWithCircuitBreaker(
                    "test",
                    () -> "success",
                    () -> "fallback"
            );

            assertEquals("success", result);
        }

        @Test
        @DisplayName("带熔断器执行Runnable - 成功")
        void shouldExecuteRunnableWithCircuitBreaker() {
            AtomicInteger counter = new AtomicInteger(0);

            resilienceService.executeWithCircuitBreaker(
                    "test",
                    counter::incrementAndGet,
                    () -> counter.set(-1)
            );

            assertEquals(1, counter.get());
        }

        @Test
        @DisplayName("带重试执行 - 成功")
        void shouldExecuteWithRetry() {
            when(resilienceConfig.getRetry()).thenReturn(new ResilienceConfig.RetryConfig());
            when(resilienceConfig.getRetry().getMaxAttempts()).thenReturn(3);
            when(resilienceConfig.getRetry().getInitialDelayMs()).thenReturn(1L);
            when(resilienceConfig.getRetry().isEnableExponentialBackoff()).thenReturn(false);
            when(resilienceConfig.getRetry().getMultiplier()).thenReturn(1.0);
            when(resilienceConfig.getRetry().getMaxDelayMs()).thenReturn(100L);

            String result = resilienceService.executeWithRetry(
                    "test",
                    () -> "success",
                    () -> "fallback"
            );

            assertEquals("success", result);
        }

        @Test
        @DisplayName("完整保护执行 - 成功")
        void shouldExecuteWithFullProtection() {
            when(resilienceConfig.getRetry()).thenReturn(new ResilienceConfig.RetryConfig());
            when(resilienceConfig.getRetry().getMaxAttempts()).thenReturn(3);
            when(resilienceConfig.getRetry().getInitialDelayMs()).thenReturn(1L);
            when(resilienceConfig.getRetry().isEnableExponentialBackoff()).thenReturn(false);
            when(resilienceConfig.getRetry().getMultiplier()).thenReturn(1.0);
            when(resilienceConfig.getRetry().getMaxDelayMs()).thenReturn(100L);

            String result = resilienceService.executeWithFullProtection(
                    "test",
                    () -> "success",
                    () -> "fallback"
            );

            assertEquals("success", result);
        }

        @Test
        @DisplayName("重置熔断器")
        void shouldResetCircuitBreaker() {
            assertDoesNotThrow(() -> resilienceService.resetCircuitBreaker("test"));
        }

        @Test
        @DisplayName("重置所有熔断器")
        void shouldResetAllCircuitBreakers() {
            assertDoesNotThrow(() -> resilienceService.resetAllCircuitBreakers());
        }

        @Test
        @DisplayName("获取熔断器指标")
        void shouldGetCircuitBreakerMetrics() {
            assertNotNull(resilienceService.getCircuitBreakerMetrics());
        }

        @Test
        @DisplayName("获取配置")
        void shouldGetConfig() {
            assertNotNull(resilienceService.getConfig());
        }
    }
}
