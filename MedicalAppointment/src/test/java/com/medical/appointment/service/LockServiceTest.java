package com.medical.appointment.service;

import com.medical.appointment.builder.TestDataBuilder;
import com.medical.appointment.service.LockService.LockEntry;
import com.medical.appointment.service.LockService.LockResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LockService 单元测试 - 名额锁定机制")
class LockServiceTest {

    private LockService lockService;

    @BeforeEach
    void setUp() {
        lockService = new LockService();
        TestDataBuilder.resetCounter();
    }

    @Nested
    @DisplayName("基本锁定功能测试")
    class BasicLockTests {

        @Test
        @DisplayName("应该成功获取分布式锁")
        void shouldAcquireLockSuccessfully() {
            String scheduleId = "schedule_001";
            String patientId = "patient_001";

            LockResult result = lockService.tryAcquireForSchedule(scheduleId, patientId, "normal");

            assertTrue(result.isAcquired(), "锁应该获取成功");
            assertNotNull(result.getLockKey(), "锁键不应为空");
            assertTrue(result.getLockKey().contains(scheduleId), "锁键应该包含排班ID");
            assertEquals("锁获取成功", result.getMessage());
        }

        @Test
        @DisplayName("应该成功释放锁")
        void shouldReleaseLockSuccessfully() {
            String scheduleId = "schedule_001";
            String patientId = "patient_001";

            lockService.tryAcquireForSchedule(scheduleId, patientId, "normal");
            assertTrue(lockService.isScheduleLocked(scheduleId), "锁应该存在");

            boolean released = lockService.releaseScheduleLock(scheduleId);

            assertTrue(released, "锁应该释放成功");
            assertFalse(lockService.isScheduleLocked(scheduleId), "锁释放后不应再被锁定");
        }

        @Test
        @DisplayName("应该正确构建锁键")
        void shouldBuildCorrectLockKey() {
            String scheduleId = "schedule_123";
            String expectedKey = "SCHEDULE_schedule_123";

            String actualKey = lockService.buildScheduleLockKey(scheduleId);

            assertEquals(expectedKey, actualKey, "锁键构建不正确");
        }
    }

    @Nested
    @DisplayName("并发冲突处理测试")
    class ConcurrentConflictTests {

        @Test
        @DisplayName("并发预约时应该正确处理锁冲突")
        void shouldHandleLockConflictCorrectly() throws Exception {
            String scheduleId = "schedule_001";
            String patient1 = "patient_001";
            String patient2 = "patient_002";

            LockResult result1 = lockService.tryAcquireForSchedule(scheduleId, patient1, "normal");
            assertTrue(result1.isAcquired(), "第一个患者应该获取到锁");

            LockResult result2 = lockService.tryAcquireForSchedule(scheduleId, patient2, "normal");
            assertFalse(result2.isAcquired(), "第二个患者不应该获取到锁");
            assertTrue(result2.getMessage().contains("锁被其他进程持有"), "错误消息应该包含锁冲突信息");
        }

        @Test
        @DisplayName("多线程并发竞争锁时只有一个能成功")
        void testMultiThreadedLockCompetition() throws Exception {
            int threadCount = 10;
            String scheduleId = "schedule_competition";
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(threadCount);
            
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);
            AtomicReference<String> winner = new AtomicReference<>(null);

            for (int i = 0; i < threadCount; i++) {
                final String patientId = "patient_" + i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        LockResult result = lockService.tryAcquireForSchedule(scheduleId, patientId, "normal");
                        if (result.isAcquired()) {
                            successCount.incrementAndGet();
                            winner.set(patientId);
                        } else {
                            failCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            endLatch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(1, successCount.get(), "应该只有一个线程能获取到锁");
            assertEquals(threadCount - 1, failCount.get(), "其他线程应该获取锁失败");
            assertNotNull(winner.get(), "应该有一个获胜者");
        }

        @Test
        @DisplayName("锁释放后其他线程应该能够获取")
        void testLockReleaseAllowsNewAcquisition() {
            String scheduleId = "schedule_release";
            String patient1 = "patient_1";
            String patient2 = "patient_2";

            LockResult result1 = lockService.tryAcquireForSchedule(scheduleId, patient1, "normal");
            assertTrue(result1.isAcquired(), "Patient1 应该获取到锁");

            lockService.releaseScheduleLock(scheduleId);

            LockResult result2 = lockService.tryAcquireForSchedule(scheduleId, patient2, "normal");
            assertTrue(result2.isAcquired(), "Patient2 应该能够在锁释放后获取到锁");
        }
    }

    @Nested
    @DisplayName("患者等级超时差异测试")
    class PatientLevelTimeoutTests {

        @Test
        @DisplayName("VIP患者应该有更短的超时时间")
        void vipPatientShouldHaveShorterTimeout() {
            long vipTimeout = lockService.getTimeoutByPatientType("VIP");
            long normalTimeout = lockService.getTimeoutByPatientType("normal");

            assertEquals(5, vipTimeout, "VIP患者超时时间应为5秒");
            assertEquals(15, normalTimeout, "普通患者超时时间应为15秒");
            assertTrue(vipTimeout < normalTimeout, "VIP患者超时时间应该更短");
        }

        @Test
        @DisplayName("普通患者应该有标准超时时间")
        void normalPatientShouldHaveStandardTimeout() {
            long timeout = lockService.getTimeoutByPatientType("NORMAL");
            assertEquals(15, timeout, "普通患者超时时间应为15秒");
        }

        @Test
        @DisplayName("未知类型患者应该使用默认超时时间")
        void unknownPatientTypeShouldUseDefault() {
            long timeout = lockService.getTimeoutByPatientType("UNKNOWN");
            assertEquals(15, timeout, "未知类型患者应使用默认超时时间");
        }

        @Test
        @DisplayName("锁超时后应该自动失效")
        void lockShouldExpireAfterTimeout() throws Exception {
            String scheduleId = "schedule_expire";
            String patientId = "patient_001";

            lockService.tryAcquire(scheduleId, patientId, Duration.ofMillis(100));
            assertTrue(lockService.isLocked(scheduleId), "锁应该存在");

            Thread.sleep(150);

            lockService.cleanExpiredLocks();
            assertFalse(lockService.isLocked(scheduleId), "超时后锁应该失效");
        }
    }

    @Nested
    @DisplayName("锁状态管理测试")
    class LockStateManagementTests {

        @Test
        @DisplayName("应该正确统计活跃锁数量")
        void shouldCountActiveLocks() {
            assertEquals(0, lockService.getActiveLockCount(), "初始状态应该没有锁");

            lockService.tryAcquireForSchedule("schedule_1", "patient_1", "normal");
            lockService.tryAcquireForSchedule("schedule_2", "patient_2", "normal");

            assertEquals(2, lockService.getActiveLockCount(), "应该有2个活跃锁");
        }

        @Test
        @DisplayName("应该正确清除所有锁")
        void shouldClearAllLocks() {
            lockService.tryAcquireForSchedule("schedule_1", "patient_1", "normal");
            lockService.tryAcquireForSchedule("schedule_2", "patient_2", "normal");

            assertTrue(lockService.getActiveLockCount() > 0, "应该存在锁");

            lockService.clearAllLocks();

            assertEquals(0, lockService.getActiveLockCount(), "清除后应该没有锁");
        }

        @Test
        @DisplayName("释放不存在的锁应该返回false")
        void releasingNonExistentLockShouldReturnFalse() {
            boolean result = lockService.releaseScheduleLock("nonexistent");
            assertFalse(result, "释放不存在的锁应该返回false");
        }
    }
}
