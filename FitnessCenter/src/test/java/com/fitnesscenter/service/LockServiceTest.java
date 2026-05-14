package com.fitnesscenter.service;

import com.fitnesscenter.builder.TestDataBuilder;
import com.fitnesscenter.lock.LockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("分布式锁服务测试")
class LockServiceTest {

    private LockService lockService;
    private static final String VIP_LEVEL = "vip";
    private static final long VIP_TIMEOUT = 10000;
    private static final long REGULAR_TIMEOUT = 30000;

    @BeforeEach
    void setUp() {
        lockService = new LockService();
        lockService.clearAllLocks();
    }

    @Test
    @DisplayName("测试VIP会员获取课程名额锁")
    void testAcquireLockForVipMember() {
        String courseId = TestDataBuilder.generateTestId("COURSE");

        boolean acquired = lockService.tryLockCourseSlot(courseId, VIP_LEVEL);

        assertTrue(acquired, "VIP会员应该能够获取锁");
        assertTrue(lockService.isCourseSlotLocked(courseId), "课程名额应该被锁定");
        assertEquals(1, lockService.getLockAcquireSuccesses(), "成功获取锁计数应该为1");
    }

    @Test
    @DisplayName("测试普通会员获取课程名额锁")
    void testAcquireLockForRegularMember() {
        String courseId = TestDataBuilder.generateTestId("COURSE");

        boolean acquired = lockService.tryLockCourseSlot(courseId, "regular");

        assertTrue(acquired, "普通会员应该能够获取锁");
        assertTrue(lockService.isCourseSlotLocked(courseId), "课程名额应该被锁定");
    }

    @Test
    @DisplayName("测试锁冲突处理 - 并发时只有一个能获取锁")
    void testLockConflictHandling() {
        String courseId = TestDataBuilder.generateTestId("COURSE");

        boolean firstAcquire = lockService.tryLockCourseSlot(courseId, VIP_LEVEL);
        boolean secondAcquire = lockService.tryLockCourseSlot(courseId, "regular");

        assertTrue(firstAcquire, "第一个应该获取到锁");
        assertFalse(secondAcquire, "第二个应该获取不到锁");
        assertEquals(1, lockService.getLockAcquireSuccesses(), "成功计数应该为1");
        assertEquals(1, lockService.getLockAcquireFailures(), "失败计数应该为1");
    }

    @Test
    @DisplayName("测试VIP会员锁超时时间 - 更短")
    void testVipMemberLockTimeoutIsShorter() {
        long vipTimeout = lockService.getLockTimeout(VIP_LEVEL);
        long regularTimeout = lockService.getLockTimeout("regular");

        assertEquals(VIP_TIMEOUT, vipTimeout, "VIP超时应该为10秒");
        assertEquals(REGULAR_TIMEOUT, regularTimeout, "普通会员超时应该为30秒");
        assertTrue(vipTimeout < regularTimeout, "VIP超时应该比普通会员短");
    }

    @Test
    @DisplayName("测试释放锁")
    void testReleaseLock() {
        String courseId = TestDataBuilder.generateTestId("COURSE");
        lockService.tryLockCourseSlot(courseId, VIP_LEVEL);

        boolean released = lockService.releaseCourseSlotLock(courseId);

        assertTrue(released, "锁应该被释放");
        assertFalse(lockService.isCourseSlotLocked(courseId), "课程名额不应该再被锁定");
    }

    @Test
    @DisplayName("测试锁释放后可以重新获取")
    void testLockCanBeReacquiredAfterRelease() {
        String courseId = TestDataBuilder.generateTestId("COURSE");

        lockService.tryLockCourseSlot(courseId, VIP_LEVEL);
        lockService.releaseCourseSlotLock(courseId);
        boolean reacquired = lockService.tryLockCourseSlot(courseId, "regular");

        assertTrue(reacquired, "锁释放后应该可以重新获取");
    }

    @Test
    @DisplayName("测试释放不存在的锁")
    void testReleaseNonExistentLock() {
        String courseId = TestDataBuilder.generateTestId("COURSE");

        boolean released = lockService.releaseCourseSlotLock(courseId);

        assertFalse(released, "不存在的锁不应该被释放");
    }

    @Test
    @DisplayName("测试并发锁获取 - 只有一个线程能成功")
    @Execution(ExecutionMode.CONCURRENT)
    void testConcurrentLockAcquisition() throws InterruptedException {
        String courseId = TestDataBuilder.generateTestId("COURSE");
        int threadCount = 10;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    boolean acquired = lockService.tryLockCourseSlot(courseId, VIP_LEVEL);
                    if (acquired) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertEquals(1, successCount.get(), "只有一个线程应该成功获取锁");
        assertEquals(threadCount - 1, failureCount.get(), "其他线程应该失败");
    }

    @Test
    @DisplayName("测试多个不同课程的锁可以同时存在")
    void testMultipleCourseLocks() {
        String course1Id = TestDataBuilder.generateTestId("COURSE");
        String course2Id = TestDataBuilder.generateTestId("COURSE");

        lockService.tryLockCourseSlot(course1Id, VIP_LEVEL);
        boolean lock2Acquired = lockService.tryLockCourseSlot(course2Id, VIP_LEVEL);

        assertTrue(lock2Acquired, "第二个课程的锁应该能够获取");
        assertTrue(lockService.isCourseSlotLocked(course1Id), "课程1应该被锁定");
        assertTrue(lockService.isCourseSlotLocked(course2Id), "课程2应该被锁定");
        assertEquals(2, lockService.getActiveLockCount(), "应该有2个活跃锁");
    }

    @Test
    @DisplayName("测试清除所有锁")
    void testClearAllLocks() {
        String course1Id = TestDataBuilder.generateTestId("COURSE");
        String course2Id = TestDataBuilder.generateTestId("COURSE");

        lockService.tryLockCourseSlot(course1Id, VIP_LEVEL);
        lockService.tryLockCourseSlot(course2Id, VIP_LEVEL);
        assertEquals(2, lockService.getActiveLockCount(), "应该有2个活跃锁");

        lockService.clearAllLocks();

        assertEquals(0, lockService.getActiveLockCount(), "清除后应该没有活跃锁");
        assertFalse(lockService.isCourseSlotLocked(course1Id), "课程1不应该被锁定");
        assertFalse(lockService.isCourseSlotLocked(course2Id), "课程2不应该被锁定");
        assertEquals(0, lockService.getLockAcquireSuccesses(), "成功计数应该重置");
        assertEquals(0, lockService.getLockAcquireFailures(), "失败计数应该重置");
    }

    @Test
    @DisplayName("测试重置统计数据")
    void testResetStats() {
        String courseId = TestDataBuilder.generateTestId("COURSE");

        lockService.tryLockCourseSlot(courseId, VIP_LEVEL);
        lockService.tryLockCourseSlot(courseId, "regular");

        lockService.resetStats();

        assertEquals(0, lockService.getLockAcquireSuccesses(), "成功计数应该重置");
        assertEquals(0, lockService.getLockAcquireFailures(), "失败计数应该重置");
        assertTrue(lockService.isCourseSlotLocked(courseId), "锁状态不应该被重置");
    }

    @Test
    @DisplayName("测试活动锁计数")
    void testActiveLockCount() {
        assertEquals(0, lockService.getActiveLockCount(), "初始状态应该没有活跃锁");

        String course1Id = TestDataBuilder.generateTestId("COURSE");
        String course2Id = TestDataBuilder.generateTestId("COURSE");

        lockService.tryLockCourseSlot(course1Id, VIP_LEVEL);
        assertEquals(1, lockService.getActiveLockCount(), "应该有1个活跃锁");

        lockService.tryLockCourseSlot(course2Id, VIP_LEVEL);
        assertEquals(2, lockService.getActiveLockCount(), "应该有2个活跃锁");

        lockService.releaseCourseSlotLock(course1Id);
        assertEquals(1, lockService.getActiveLockCount(), "释放一个后应该有1个活跃锁");
    }
}
