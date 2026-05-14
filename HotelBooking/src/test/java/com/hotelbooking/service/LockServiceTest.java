package com.hotelbooking.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LockServiceTest {

    private LockService lockService;

    @BeforeEach
    void setUp() {
        lockService = new LockService();
    }

    @Test
    @DisplayName("测试分布式锁 - 成功获取锁")
    void testTryLock_SuccessfulAcquisition() {
        String lockKey = "room_001";
        String owner = "user_001";

        boolean acquired = lockService.tryLock(lockKey, owner, LockService.CustomerLevel.NORMAL);

        assertTrue(acquired);
        assertTrue(lockService.isLocked(lockKey));
        assertEquals(owner, lockService.getLockOwner(lockKey));
    }

    @Test
    @DisplayName("测试分布式锁 - 同一owner重复获取应失败")
    void testTryLock_DuplicateAcquisition_ShouldFail() {
        String lockKey = "room_001";
        String owner1 = "user_001";
        String owner2 = "user_002";

        boolean acquired1 = lockService.tryLock(lockKey, owner1, LockService.CustomerLevel.NORMAL);
        boolean acquired2 = lockService.tryLock(lockKey, owner2, LockService.CustomerLevel.NORMAL);

        assertTrue(acquired1);
        assertFalse(acquired2);
    }

    @Test
    @DisplayName("测试分布式锁 - 释放锁后可重新获取")
    void testTryLock_AfterRelease_ShouldAllowNewAcquisition() {
        String lockKey = "room_001";
        String owner1 = "user_001";
        String owner2 = "user_002";

        lockService.tryLock(lockKey, owner1, LockService.CustomerLevel.NORMAL);
        lockService.releaseLock(lockKey, owner1);

        assertFalse(lockService.isLocked(lockKey));

        boolean acquiredBySecond = lockService.tryLock(lockKey, owner2, LockService.CustomerLevel.NORMAL);
        assertTrue(acquiredBySecond);
        assertEquals(owner2, lockService.getLockOwner(lockKey));
    }

    @Test
    @DisplayName("测试不同客户等级锁定超时差异 - VIP短超时")
    void testLockTimeout_DifferentCustomerLevels() {
        assertEquals(3000, LockService.CustomerLevel.VIP.getTimeoutMillis());
        assertEquals(10000, LockService.CustomerLevel.NORMAL.getTimeoutMillis());
        
        assertTrue(LockService.CustomerLevel.VIP.getTimeoutMillis() < 
                   LockService.CustomerLevel.NORMAL.getTimeoutMillis());
    }

    @Test
    @DisplayName("测试并发锁竞争 - 多线程竞争同一锁")
    void testConcurrentLockCompetition_MultipleThreads() throws InterruptedException {
        int threadCount = 10;
        String lockKey = "shared_lock";
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final String owner = "thread_" + i;
            executor.submit(() -> {
                try {
                    boolean acquired = lockService.tryLock(lockKey, owner, LockService.CustomerLevel.NORMAL);
                    if (acquired) {
                        successCount.incrementAndGet();
                        Thread.sleep(100);
                        lockService.releaseLock(lockKey, owner);
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(successCount.get() >= 1, "至少应有一个线程成功获取锁");
        assertEquals(threadCount, successCount.get() + failCount.get());
    }

    @Test
    @DisplayName("测试锁定释放时序 - 正确的锁释放")
    void testLockReleaseSequence_CorrectReleaseOrder() {
        String lockKey = "room_sequence";

        lockService.tryLock(lockKey, "first", LockService.CustomerLevel.NORMAL);
        assertTrue(lockService.isLocked(lockKey));

        lockService.releaseLock(lockKey, "first");
        assertFalse(lockService.isLocked(lockKey));

        lockService.tryLock(lockKey, "second", LockService.CustomerLevel.NORMAL);
        assertTrue(lockService.isLocked(lockKey));
        assertEquals("second", lockService.getLockOwner(lockKey));
    }

    @Test
    @DisplayName("测试错误owner无法释放锁")
    void testReleaseLock_WrongOwner_ShouldNotRelease() {
        String lockKey = "room_protected";
        String owner = "correct_owner";
        String wrongOwner = "wrong_owner";

        lockService.tryLock(lockKey, owner, LockService.CustomerLevel.NORMAL);

        lockService.releaseLock(lockKey, wrongOwner);

        assertTrue(lockService.isLocked(lockKey));
        assertEquals(owner, lockService.getLockOwner(lockKey));
    }

    @Test
    @DisplayName("测试未锁定时isLocked返回false")
    void testIsLocked_UnlockedLock_ShouldReturnFalse() {
        assertFalse(lockService.isLocked("nonexistent_key"));
    }

    @Test
    @DisplayName("测试未锁定时getLockOwner返回null")
    void testGetLockOwner_UnlockedLock_ShouldReturnNull() {
        assertNull(lockService.getLockOwner("nonexistent_key"));
    }

    @Test
    @DisplayName("测试多个独立锁互不影响")
    void testMultipleIndependentLocks_ShouldNotInterfere() {
        String lockKey1 = "room_001";
        String lockKey2 = "room_002";

        lockService.tryLock(lockKey1, "user_001", LockService.CustomerLevel.NORMAL);
        lockService.tryLock(lockKey2, "user_002", LockService.CustomerLevel.NORMAL);

        assertTrue(lockService.isLocked(lockKey1));
        assertTrue(lockService.isLocked(lockKey2));
        assertEquals("user_001", lockService.getLockOwner(lockKey1));
        assertEquals("user_002", lockService.getLockOwner(lockKey2));

        lockService.releaseLock(lockKey1, "user_001");

        assertFalse(lockService.isLocked(lockKey1));
        assertTrue(lockService.isLocked(lockKey2));
    }

    @Test
    @DisplayName("测试锁超时过期后可重新获取")
    void testLockExpiration_AfterTimeout_ShouldAllowNewAcquisition() throws InterruptedException {
        String lockKey = "timed_lock";
        String owner = "temp_owner";

        lockService.tryLock(lockKey, owner, LockService.CustomerLevel.NORMAL);

        assertTrue(lockService.isLocked(lockKey));

        Thread.sleep(LockService.CustomerLevel.NORMAL.getTimeoutMillis() + 100);

        String newOwner = "new_owner";
        boolean acquired = lockService.tryLock(lockKey, newOwner, LockService.CustomerLevel.NORMAL);

        assertTrue(acquired);
        assertEquals(newOwner, lockService.getLockOwner(lockKey));
    }

    @Test
    @DisplayName("测试锁竞争与释放 - 高并发场景")
    void testHighConcurrencyLocking_ShouldBeThreadSafe() throws InterruptedException {
        int iterations = 100;
        String lockKey = "high_concurrency";
        AtomicInteger lockHeld = new AtomicInteger(0);
        AtomicInteger violations = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(iterations);
        ExecutorService executor = Executors.newFixedThreadPool(20);

        for (int i = 0; i < iterations; i++) {
            final int threadNum = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    String owner = "t" + threadNum;
                    if (lockService.tryLock(lockKey, owner, LockService.CustomerLevel.NORMAL)) {
                        if (lockHeld.incrementAndGet() != 1) {
                            violations.incrementAndGet();
                        }
                        Thread.yield();
                        if (lockHeld.decrementAndGet() != 0) {
                            violations.incrementAndGet();
                        }
                        lockService.releaseLock(lockKey, owner);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(0, violations.get());
    }
}
