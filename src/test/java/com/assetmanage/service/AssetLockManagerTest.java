package com.assetmanage.service;

import com.assetmanage.common.AssetLockManager;
import com.assetmanage.testdata.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.SAME_THREAD)
class AssetLockManagerTest {

    private AssetLockManager lockManager;
    private static final String TEST_ASSET_ID = TestDataBuilder.TEST_ASSET_ID;
    private static final String USER_1 = TestDataBuilder.TEST_USER_ID_1;
    private static final String USER_2 = TestDataBuilder.TEST_USER_ID_2;

    @BeforeEach
    void setUp() {
        lockManager = new AssetLockManager();
    }

    @Test
    @DisplayName("测试用户成功获取资产锁定")
    void testSuccessfulLockAcquisition() {
        boolean result = lockManager.tryLock(TEST_ASSET_ID, USER_1);
        
        assertTrue(result, "第一个用户应该成功获取锁");
        assertTrue(lockManager.isLocked(TEST_ASSET_ID), "资产应该处于锁定状态");
        assertEquals(USER_1, lockManager.getLockOwner(TEST_ASSET_ID), "锁定所有者应该是第一个用户");
    }

    @Test
    @DisplayName("测试多用户并发领用同一资产时的状态锁定 - 只有一个用户能获取锁")
    void testConcurrentLockOnlyOneUserSucceeds() throws InterruptedException {
        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            String userId = "user_" + i;
            new Thread(() -> {
                try {
                    startLatch.await();
                    boolean result = lockManager.tryLock(TEST_ASSET_ID, userId, 30, TimeUnit.SECONDS);
                    if (result) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        endLatch.await(5, TimeUnit.SECONDS);

        assertEquals(1, successCount.get(), "应该只有一个用户成功获取锁");
        assertEquals(threadCount - 1, failCount.get(), "其他用户应该获取锁失败");
    }

    @Test
    @DisplayName("测试锁定获取与释放时序 - 释放后其他用户可获取")
    void testLockReleaseAndReacquire() {
        assertTrue(lockManager.tryLock(TEST_ASSET_ID, USER_1), "第一个用户获取锁");
        
        boolean user2Failed = lockManager.tryLock(TEST_ASSET_ID, USER_2);
        assertFalse(user2Failed, "锁未释放时，第二个用户不能获取");

        assertTrue(lockManager.releaseLock(TEST_ASSET_ID, USER_1), "锁应该被正确释放");
        assertFalse(lockManager.isLocked(TEST_ASSET_ID), "锁释放后资产不应处于锁定状态");

        assertTrue(lockManager.tryLock(TEST_ASSET_ID, USER_2), "锁释放后，第二个用户应该能获取");
        assertEquals(USER_2, lockManager.getLockOwner(TEST_ASSET_ID), "锁定所有者应该是第二个用户");
    }

    @Test
    @DisplayName("测试非锁定者不能释放锁")
    void testNonOwnerCannotReleaseLock() {
        lockManager.tryLock(TEST_ASSET_ID, USER_1);
        
        boolean released = lockManager.releaseLock(TEST_ASSET_ID, USER_2);
        assertFalse(released, "非锁定者不能释放锁");
        assertTrue(lockManager.isLocked(TEST_ASSET_ID), "锁应该仍然存在");
        assertEquals(USER_1, lockManager.getLockOwner(TEST_ASSET_ID), "锁定所有者应该仍是第一个用户");
    }

    @Test
    @DisplayName("测试锁定超时自动释放")
    void testLockTimeoutAutoRelease() throws InterruptedException {
        boolean acquired = lockManager.tryLock(TEST_ASSET_ID, USER_1, 1, TimeUnit.SECONDS);
        assertTrue(acquired, "应该成功获取锁");
        assertTrue(lockManager.isLocked(TEST_ASSET_ID), "锁应该存在");

        Thread.sleep(1500);
        
        assertFalse(lockManager.isLocked(TEST_ASSET_ID), "超时后锁应该被自动释放");
        assertNull(lockManager.getLockOwner(TEST_ASSET_ID), "锁定所有者应该为空");

        assertTrue(lockManager.tryLock(TEST_ASSET_ID, USER_2), "超时后其他用户应该能获取锁");
    }

    @Test
    @DisplayName("测试并发领用下不出现重复领用")
    void testNoDuplicateUsageInConcurrentScenario() throws InterruptedException {
        int threadCount = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger usageCount = new AtomicInteger(0);
        AtomicReference<String> successfulUser = new AtomicReference<>(null);

        for (int i = 0; i < threadCount; i++) {
            String userId = "user_" + i;
            new Thread(() -> {
                try {
                    startLatch.await();
                    if (lockManager.tryLock(TEST_ASSET_ID, userId)) {
                        int current = usageCount.incrementAndGet();
                        if (current == 1) {
                            successfulUser.set(userId);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        endLatch.await(5, TimeUnit.SECONDS);

        assertEquals(1, usageCount.get(), "并发情况下应该只有一个用户成功领用");
        assertNotNull(successfulUser.get(), "应该有一个用户成功");
    }

    @Test
    @DisplayName("测试多个资产的独立锁定")
    void testMultipleAssetsIndependentLocks() {
        String asset1 = "asset_001";
        String asset2 = "asset_002";

        assertTrue(lockManager.tryLock(asset1, USER_1), "用户1获取资产1的锁");
        assertTrue(lockManager.tryLock(asset2, USER_2), "用户2应该能获取资产2的锁，与资产1无关");

        assertTrue(lockManager.isLocked(asset1), "资产1应该被锁定");
        assertTrue(lockManager.isLocked(asset2), "资产2应该被锁定");

        assertEquals(USER_1, lockManager.getLockOwner(asset1), "资产1的所有者应该是用户1");
        assertEquals(USER_2, lockManager.getLockOwner(asset2), "资产2的所有者应该是用户2");
    }

    @Test
    @DisplayName("测试锁定计数")
    void testLockCount() {
        assertEquals(0, lockManager.getActiveLockCount(), "初始状态应该没有锁");

        lockManager.tryLock("asset_1", USER_1);
        assertEquals(1, lockManager.getActiveLockCount(), "应该有1个锁");

        lockManager.tryLock("asset_2", USER_2);
        assertEquals(2, lockManager.getActiveLockCount(), "应该有2个锁");

        lockManager.releaseLock("asset_1", USER_1);
        assertEquals(1, lockManager.getActiveLockCount(), "释放后应该有1个锁");
    }

    @Test
    @DisplayName("测试强制释放锁")
    void testForceReleaseLock() {
        lockManager.tryLock(TEST_ASSET_ID, USER_1);
        assertTrue(lockManager.isLocked(TEST_ASSET_ID));

        lockManager.forceReleaseLock(TEST_ASSET_ID);
        assertFalse(lockManager.isLocked(TEST_ASSET_ID), "强制释放后锁应该不存在");
    }

    @Test
    @DisplayName("测试释放不存在的锁")
    void testReleaseNonExistentLock() {
        boolean result = lockManager.releaseLock("non_existent", USER_1);
        assertTrue(result, "释放不存在的锁应该返回true");
    }
}
