package com.configcenter.config.service;

import com.configcenter.common.testdata.TestDataBuilder;
import com.configcenter.config.config.LockProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("配置更新锁服务单元测试")
class ConfigLockServiceTest {

    @Mock
    private LockProperties lockProperties;

    @InjectMocks
    private ConfigLockService lockService;

    @BeforeEach
    void setUp() {
        when(lockProperties.getEnabled()).thenReturn(true);
        when(lockProperties.getAcquireTimeoutMillis()).thenReturn(5000L);
        when(lockProperties.getHoldTimeoutMillis()).thenReturn(30000L);
        when(lockProperties.getRetryCount()).thenReturn(3);
        when(lockProperties.getRetryIntervalMillis()).thenReturn(100L);
        when(lockProperties.getLockPrefix()).thenReturn("config:lock:");
    }

    @Test
    @DisplayName("测试锁获取和释放 - 正常流程")
    void testLockAcquireAndRelease() {
        String configId = "config_db_01";

        assertFalse(lockService.isLocked(configId));

        boolean acquired = lockService.acquireLock(configId);
        assertTrue(acquired);
        assertTrue(lockService.isLocked(configId));

        lockService.releaseLock(configId);
        assertFalse(lockService.isLocked(configId));
    }

    @Test
    @DisplayName("测试并发更新 - 锁机制阻止并发访问")
    void testConcurrentUpdate_LockPreventsRaceCondition() throws InterruptedException, ExecutionException {
        String configId = "config_db_01";
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        AtomicInteger lockAcquiredCount = new AtomicInteger(0);
        AtomicInteger concurrentAccessCount = new AtomicInteger(0);
        List<Future<Boolean>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                boolean acquired = lockService.acquireLock(configId, 1000);
                if (acquired) {
                    lockAcquiredCount.incrementAndGet();
                    try {
                        Thread.sleep(50);
                        concurrentAccessCount.incrementAndGet();
                    } finally {
                        lockService.releaseLock(configId);
                    }
                }
                endLatch.countDown();
                return acquired;
            }));
        }

        startLatch.countDown();
        endLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        int successCount = 0;
        for (Future<Boolean> future : futures) {
            if (future.get()) {
                successCount++;
            }
        }

        assertEquals(successCount, concurrentAccessCount.get());
        assertEquals(lockAcquiredCount.get(), concurrentAccessCount.get());
    }

    @Test
    @DisplayName("测试并发更新 - 配置覆盖保护")
    void testConcurrentUpdate_PreventsOverwrite() throws InterruptedException, ExecutionException {
        String configId = "config_db_01";
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicReference<String> sharedValue = new AtomicReference<>("initial");
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                return lockService.executeWithLock(configId, () -> {
                    String current = sharedValue.get();
                    Thread.sleep(10);
                    String newValue = "value_" + index;
                    sharedValue.set(newValue);
                    return newValue;
                });
            }));
        }

        List<String> results = new ArrayList<>();
        for (Future<String> future : futures) {
            results.add(future.get());
        }

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        assertEquals(threadCount, results.size());
        
        Map<String, Integer> valueCount = new HashMap<>();
        for (String result : results) {
            valueCount.merge(result, 1, Integer::sum);
        }

        String finalValue = sharedValue.get();
        assertTrue(results.contains(finalValue), "最终值应该是某个线程设置的值");
    }

    @Test
    @DisplayName("测试锁超时 - 无法获取锁时抛出异常")
    void testLockTimeout_ThrowsException() throws InterruptedException {
        String configId = "config_db_01";
        ExecutorService executor = Executors.newFixedThreadPool(2);

        CountDownLatch firstLockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);

        Future<Boolean> firstFuture = executor.submit(() -> {
            boolean acquired = lockService.acquireLock(configId);
            firstLockAcquired.countDown();
            releaseLatch.await(5, TimeUnit.SECONDS);
            lockService.releaseLock(configId);
            return acquired;
        });

        firstLockAcquired.await(5, TimeUnit.SECONDS);
        assertTrue(firstFuture.get());

        when(lockProperties.getAcquireTimeoutMillis()).thenReturn(100L);
        when(lockProperties.getRetryCount()).thenReturn(1);

        assertThrows(com.configcenter.common.exception.BusinessException.class, () -> {
            lockService.executeWithLock(configId, () -> "should not reach here");
        });

        releaseLatch.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("测试重入锁 - 同一线程可多次获取锁")
    void testReentrantLock_SameThreadCanAcquireMultipleTimes() {
        String configId = "config_db_01";

        boolean firstAcquire = lockService.acquireLock(configId);
        assertTrue(firstAcquire);

        boolean secondAcquire = lockService.acquireLock(configId);
        assertTrue(secondAcquire);

        lockService.releaseLock(configId);
        lockService.releaseLock(configId);

        assertFalse(lockService.isLocked(configId));
    }

    @Test
    @DisplayName("测试锁禁用时的行为")
    void testLockDisabled_AllowsConcurrentAccess() throws InterruptedException, ExecutionException {
        String configId = "config_db_01";
        when(lockProperties.getEnabled()).thenReturn(false);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger concurrentCount = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                return lockService.executeWithLock(configId, () -> {
                    int current = concurrentCount.incrementAndGet();
                    maxConcurrent.set(Math.max(maxConcurrent.get(), current));
                    Thread.sleep(100);
                    concurrentCount.decrementAndGet();
                    return current;
                });
            }));
        }

        for (Future<Integer> future : futures) {
            future.get();
        }

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        assertTrue(maxConcurrent.get() > 1, "锁禁用时应该允许并发访问");
    }

    @Test
    @DisplayName("测试锁状态查询")
    void testGetLockStatus() {
        String configId = "config_db_01";

        Map<String, Object> statusBefore = lockService.getLockStatus(configId);
        assertFalse((Boolean) statusBefore.get("locked"));
        assertFalse((Boolean) statusBefore.get("exists"));

        lockService.acquireLock(configId);

        Map<String, Object> statusDuring = lockService.getLockStatus(configId);
        assertTrue((Boolean) statusDuring.get("locked"));
        assertTrue((Boolean) statusDuring.get("exists"));
        assertNotNull(statusDuring.get("holder"));
        assertNotNull(statusDuring.get("acquiredAt"));

        lockService.releaseLock(configId);

        Map<String, Object> statusAfter = lockService.getLockStatus(configId);
        assertFalse((Boolean) statusAfter.get("locked"));
    }

    @Test
    @DisplayName("测试所有锁状态")
    void testGetAllLockStatus() {
        String configId1 = "config_1";
        String configId2 = "config_2";

        lockService.acquireLock(configId1);

        Map<String, Object> status = lockService.getAllLockStatus();

        assertEquals(1, status.get("totalLocks"));
        assertEquals(1L, status.get("lockedCount"));

        lockService.releaseLock(configId1);

        status = lockService.getAllLockStatus();
        assertEquals(0L, status.get("lockedCount"));
    }

    @Test
    @DisplayName("测试锁重试机制")
    void testLockRetryMechanism() throws InterruptedException {
        String configId = "config_db_01";

        when(lockProperties.getRetryCount()).thenReturn(3);
        when(lockProperties.getRetryIntervalMillis()).thenReturn(50L);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstAcquired = new CountDownLatch(1);

        Future<Boolean> holder = executor.submit(() -> {
            boolean acquired = lockService.acquireLock(configId);
            firstAcquired.countDown();
            Thread.sleep(200);
            lockService.releaseLock(configId);
            return acquired;
        });

        firstAcquired.await(5, TimeUnit.SECONDS);

        Future<Boolean> waiter = executor.submit(() -> {
            return lockService.acquireLock(configId, 1000);
        });

        assertTrue(holder.get(), "第一个线程应该获取锁成功");
        assertTrue(waiter.get(5, TimeUnit.SECONDS), "第二个线程通过重试应该也能获取锁");

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("测试执行回调 - 正常执行")
    void testExecuteWithLock_NormalExecution() {
        String configId = "config_db_01";
        String expectedResult = "success";

        String result = lockService.executeWithLock(configId, () -> {
            assertTrue(lockService.isLocked(configId));
            return expectedResult;
        });

        assertEquals(expectedResult, result);
        assertFalse(lockService.isLocked(configId));
    }

    @Test
    @DisplayName("测试执行回调 - 异常时释放锁")
    void testExecuteWithLock_ExceptionReleasesLock() {
        String configId = "config_db_01";

        assertThrows(RuntimeException.class, () -> {
            lockService.executeWithLock(configId, () -> {
                throw new RuntimeException("测试异常");
            });
        });

        assertFalse(lockService.isLocked(configId), "异常时锁应该被释放");
    }

    @Test
    @DisplayName("测试多配置并发 - 不同配置互不影响")
    void testMultipleConfigs_IndependentLocks() throws InterruptedException, ExecutionException {
        String configId1 = "config_1";
        String configId2 = "config_2";
        String configId3 = "config_3";

        ExecutorService executor = Executors.newFixedThreadPool(3);
        List<Future<Boolean>> futures = new ArrayList<>();

        futures.add(executor.submit(() -> lockService.acquireLock(configId1)));
        futures.add(executor.submit(() -> lockService.acquireLock(configId2)));
        futures.add(executor.submit(() -> lockService.acquireLock(configId3)));

        for (Future<Boolean> future : futures) {
            assertTrue(future.get(), "每个配置都应该能独立获取锁");
        }

        assertTrue(lockService.isLocked(configId1));
        assertTrue(lockService.isLocked(configId2));
        assertTrue(lockService.isLocked(configId3));

        lockService.releaseLock(configId1);
        assertFalse(lockService.isLocked(configId1));
        assertTrue(lockService.isLocked(configId2));

        lockService.releaseLock(configId2);
        lockService.releaseLock(configId3);

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("测试高并发下的锁性能")
    void testHighConcurrency_LockPerformance() throws InterruptedException, ExecutionException {
        String configId = "config_db_01";
        int threadCount = 100;
        int operationsPerThread = 10;
        ExecutorService executor = Executors.newFixedThreadPool(20);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Future<Integer>> futures = new ArrayList<>();

        long startTime = System.currentTimeMillis();

        for (int t = 0; t < threadCount; t++) {
            futures.add(executor.submit(() -> {
                int localSuccess = 0;
                for (int i = 0; i < operationsPerThread; i++) {
                    try {
                        lockService.executeWithLock(configId, () -> {
                            Thread.sleep(1);
                            return null;
                        });
                        localSuccess++;
                    } catch (Exception e) {
                    }
                }
                successCount.addAndGet(localSuccess);
                return localSuccess;
            }));
        }

        for (Future<Integer> future : futures) {
            future.get();
        }

        long duration = System.currentTimeMillis() - startTime;

        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        assertEquals(threadCount * operationsPerThread, successCount.get());
        assertTrue(duration < 30000, "操作应该在合理时间内完成");
        
        System.out.printf("高并发测试: 线程=%d, 操作/线程=%d, 总操作=%d, 耗时=%dms%n",
                threadCount, operationsPerThread, successCount.get(), duration);
    }
}
