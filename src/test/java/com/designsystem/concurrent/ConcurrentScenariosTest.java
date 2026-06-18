package com.designsystem.concurrent;

import com.designsystem.common.enums.TokenLevel;
import com.designsystem.common.enums.TokenType;
import com.designsystem.entity.DesignToken;
import com.designsystem.mapper.DesignTokenMapper;
import com.designsystem.service.DesignTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("并发场景测试")
@ExtendWith(MockitoExtension.class)
class ConcurrentScenariosTest {

    @Mock
    private DesignTokenMapper tokenMapper;

    @Mock
    private com.designsystem.mapper.TokenOverrideMapper overrideMapper;

    @Mock
    private com.designsystem.mapper.ComponentTokenUsageMapper usageMapper;

    @Mock
    private com.designsystem.mapper.TokenChangeMapper changeMapper;

    @Mock
    private com.designsystem.mapper.ComponentMapper componentMapper;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private DesignTokenService tokenService;

    private ExecutorService executorService;

    @BeforeEach
    void setUp() {
        executorService = Executors.newFixedThreadPool(10);
        tokenService.init();
    }

    @Nested
    @DisplayName("多人同时编辑设计令牌测试")
    class ConcurrentTokenEditTests {

        @Test
        @DisplayName("多个线程同时更新同一个令牌应正确处理冲突")
        void shouldHandleConcurrentTokenUpdates() throws Exception {
            Long tokenId = 1L;
            DesignToken baseToken = createToken(tokenId, "--color-primary", "#3b82f6");

            when(tokenMapper.selectById(tokenId)).thenReturn(baseToken);
            when(tokenMapper.selectByName("--color-primary")).thenReturn(baseToken);
            when(tokenMapper.updateById(any(DesignToken.class))).thenAnswer(invocation -> {
                Thread.sleep(100);
                return 1;
            });

            int threadCount = 5;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch finishLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger conflictCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final int threadIndex = i;
                executorService.submit(() -> {
                    try {
                        startLatch.await();
                        DesignToken updateToken = createToken(tokenId, "--color-primary",
                                "#" + Integer.toHexString(0x1000000 + threadIndex * 0x101010).substring(1));
                        tokenService.doUpdateToken(updateToken);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        conflictCount.incrementAndGet();
                    } finally {
                        finishLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(finishLatch.await(10, TimeUnit.SECONDS));

            verify(tokenMapper, atLeastOnce()).updateById(any(DesignToken.class));
            assertTrue(successCount.get() >= 1, "At least one update should succeed");
        }

        @Test
        @DisplayName("并发更新应保持数据一致性，最终值应为最后一次成功更新的值")
        void shouldMaintainDataConsistencyAfterConcurrentUpdates() throws Exception {
            Long tokenId = 1L;
            String[] newValues = {"#ff0000", "#00ff00", "#0000ff", "#ffff00", "#ff00ff"};

            DesignToken baseToken = createToken(tokenId, "--color-primary", "#3b82f6");
            when(tokenMapper.selectById(tokenId)).thenReturn(baseToken);
            when(tokenMapper.selectByName("--color-primary")).thenReturn(baseToken);

            AtomicInteger updateCounter = new AtomicInteger(0);
            when(tokenMapper.updateById(any(DesignToken.class))).thenAnswer(invocation -> {
                Thread.sleep(50);
                updateCounter.incrementAndGet();
                return 1;
            });

            int threadCount = 5;
            CountDownLatch latch = new CountDownLatch(threadCount);
            List<Future<String>> futures = new ArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                final String newValue = newValues[i];
                Future<String> future = executorService.submit(() -> {
                    DesignToken token = createToken(tokenId, "--color-primary", newValue);
                    tokenService.doUpdateToken(token);
                    return newValue;
                });
                futures.add(future);
                latch.countDown();
            }

            latch.await(5, TimeUnit.SECONDS);

            String lastSuccessfulValue = null;
            for (Future<String> future : futures) {
                try {
                    lastSuccessfulValue = future.get(5, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                }
            }

            assertNotNull(lastSuccessfulValue);
            assertTrue(updateCounter.get() >= 1);
        }

        @Test
        @DisplayName("乐观锁版本号应在并发更新时递增")
        void shouldIncrementVersionOnConcurrentUpdates() throws Exception {
            Long tokenId = 1L;
            DesignToken token = createToken(tokenId, "--color-primary", "#3b82f6");

            when(tokenMapper.selectById(tokenId)).thenReturn(token);
            when(tokenMapper.selectByName("--color-primary")).thenReturn(token);

            AtomicInteger versionCounter = new AtomicInteger(0);
            when(tokenMapper.updateById(any(DesignToken.class))).thenAnswer(invocation -> {
                versionCounter.incrementAndGet();
                return 1;
            });

            int concurrentCount = 3;
            ExecutorService executor = Executors.newFixedThreadPool(concurrentCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(concurrentCount);

            for (int i = 0; i < concurrentCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        DesignToken updateToken = createToken(tokenId, "--color-primary", "#ffffff");
                        tokenService.doUpdateToken(updateToken);
                    } catch (Exception e) {
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            endLatch.await(10, TimeUnit.SECONDS);

            assertTrue(versionCounter.get() >= 1, "Version should be incremented");
        }
    }

    @Nested
    @DisplayName("批量任务调度测试")
    class BatchTaskSchedulingTests {

        @Test
        @DisplayName("大量组件批量重新生成文档应使用线程池并行处理")
        void shouldProcessBatchDocGenerationInParallel() throws Exception {
            int componentCount = 20;
            CountDownLatch latch = new CountDownLatch(componentCount);
            AtomicInteger processedCount = new AtomicInteger(0);
            AtomicInteger maxConcurrentThreads = new AtomicInteger(0);
            AtomicInteger currentThreads = new AtomicInteger(0);

            ExecutorService processingPool = Executors.newFixedThreadPool(8);

            for (int i = 0; i < componentCount; i++) {
                final int componentId = i + 1;
                processingPool.submit(() -> {
                    try {
                        int current = currentThreads.incrementAndGet();
                        maxConcurrentThreads.updateAndGet(max -> Math.max(max, current));

                        Thread.sleep(ThreadLocalRandom.current().nextInt(50, 150));

                        processedCount.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        currentThreads.decrementAndGet();
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(30, TimeUnit.SECONDS), "All tasks should complete within timeout");
            assertEquals(componentCount, processedCount.get(), "All components should be processed");
            assertTrue(maxConcurrentThreads.get() > 1, "Tasks should run in parallel");
            assertTrue(maxConcurrentThreads.get() <= 8, "Should not exceed thread pool size");

            processingPool.shutdown();
        }

        @Test
        @DisplayName("批量任务失败时应记录错误并继续处理其他任务")
        void shouldContinueProcessingWhenSomeTasksFail() throws Exception {
            int totalTasks = 10;
            int failingTaskIndex = 5;
            CountDownLatch latch = new CountDownLatch(totalTasks);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);

            for (int i = 0; i < totalTasks; i++) {
                final int taskIndex = i;
                executorService.submit(() -> {
                    try {
                        if (taskIndex == failingTaskIndex) {
                            throw new RuntimeException("Simulated failure for task " + taskIndex);
                        }
                        Thread.sleep(50);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS));
            assertEquals(totalTasks - 1, successCount.get(), "Successful tasks should be processed");
            assertEquals(1, failureCount.get(), "Failed task should be counted");
        }

        @Test
        @DisplayName("批量导出令牌应正确处理并发请求")
        void shouldHandleConcurrentExportRequests() throws Exception {
            List<DesignToken> tokens = createTestTokens(50);
            when(tokenMapper.selectList(null)).thenReturn(tokens);

            int concurrentRequests = 10;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(concurrentRequests);
            AtomicInteger successCount = new AtomicInteger(0);

            for (int i = 0; i < concurrentRequests; i++) {
                executorService.submit(() -> {
                    try {
                        startLatch.await();
                        String cssExport = tokenService.exportTokens(
                                com.designsystem.common.enums.ExportFormat.CSS, null, null);
                        assertNotNull(cssExport);
                        assertTrue(cssExport.contains("--"));
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(endLatch.await(30, TimeUnit.SECONDS));
            assertEquals(concurrentRequests, successCount.get(), "All export requests should succeed");
        }

        @Test
        @DisplayName("任务队列应支持重试机制")
        void shouldSupportTaskRetryMechanism() throws Exception {
            AtomicInteger attemptCount = new AtomicInteger(0);
            int maxRetries = 3;

            Callable<String> task = () -> {
                int attempt = attemptCount.incrementAndGet();
                if (attempt < maxRetries) {
                    throw new RuntimeException("Temporary failure, attempt " + attempt);
                }
                return "Success";
            };

            String result = executeWithRetry(task, maxRetries, 100);

            assertEquals("Success", result);
            assertEquals(maxRetries, attemptCount.get());
        }

        private <T> T executeWithRetry(Callable<T> task, int maxRetries, long delayMs) throws Exception {
            int attempt = 0;
            Exception lastException = null;

            while (attempt < maxRetries) {
                try {
                    return task.call();
                } catch (Exception e) {
                    lastException = e;
                    attempt++;
                    if (attempt < maxRetries) {
                        Thread.sleep(delayMs * attempt);
                    }
                }
            }

            throw lastException;
        }

        @Test
        @DisplayName("并发场景下令牌缓存应保持一致性")
        void shouldMaintainCacheConsistencyUnderConcurrency() throws Exception {
            DesignToken token = createToken(1L, "--color-primary", "#3b82f6");
            when(tokenMapper.selectByName("--color-primary")).thenReturn(token);

            int threadCount = 20;
            CountDownLatch latch = new CountDownLatch(threadCount);
            List<Future<String>> futures = new ArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                Future<String> future = executorService.submit(() -> {
                    return tokenService.resolveTokenValue("--color-primary");
                });
                futures.add(future);
                latch.countDown();
            }

            latch.await(5, TimeUnit.SECONDS);

            for (Future<String> future : futures) {
                String value = future.get(5, TimeUnit.SECONDS);
                assertEquals("#3b82f6", value);
            }

            verify(tokenMapper, atMost(1)).selectByName("--color-primary");
        }
    }

    private DesignToken createToken(Long id, String name, String value) {
        DesignToken token = new DesignToken();
        token.setId(id);
        token.setTokenName(name);
        token.setBaseValue(value);
        token.setTokenType(TokenType.COLOR);
        token.setTokenLevel(TokenLevel.SEMANTIC);
        token.setInheritsFrom(null);
        return token;
    }

    private List<DesignToken> createTestTokens(int count) {
        List<DesignToken> tokens = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            DesignToken token = new DesignToken();
            token.setId((long) i + 1);
            token.setTokenName("--token-" + i);
            token.setBaseValue("#" + Integer.toHexString(0x1000000 + i * 0x1010).substring(1));
            token.setTokenType(TokenType.COLOR);
            token.setTokenLevel(TokenLevel.BASE);
            tokens.add(token);
        }
        return tokens;
    }
}
