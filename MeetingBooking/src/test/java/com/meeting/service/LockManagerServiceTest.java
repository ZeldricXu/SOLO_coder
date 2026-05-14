package com.meeting.service;

import com.meeting.service.LockManagerService.LockResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("锁管理服务单元测试")
class LockManagerServiceTest {

    private LockManagerService lockManagerService;

    @BeforeEach
    void setUp() {
        lockManagerService = new LockManagerService();
    }

    @Test
    @DisplayName("获取锁成功 - 会议室未被锁定时应获取锁成功")
    void acquireLock_ShouldSucceed_WhenRoomNotLocked() {
        String roomId = "room_001";
        String ownerId = "user_001";

        LockResult result = lockManagerService.acquireLock(roomId, ownerId, "regular");

        assertTrue(result.isAcquired());
        assertEquals(ownerId, result.getLockOwner());
        assertTrue(lockManagerService.isRoomLocked(roomId));
    }

    @Test
    @DisplayName("获取锁失败 - 会议室已被其他请求锁定时应返回冲突")
    void acquireLock_ShouldFail_WhenRoomAlreadyLocked() {
        String roomId = "room_001";
        String firstOwner = "user_001";
        String secondOwner = "user_002";

        LockResult firstResult = lockManagerService.acquireLock(roomId, firstOwner, "regular");
        assertTrue(firstResult.isAcquired());

        LockResult secondResult = lockManagerService.acquireLock(roomId, secondOwner, "regular");

        assertFalse(secondResult.isAcquired());
        assertEquals(firstOwner, secondResult.getLockOwner());
        assertEquals("会议室正在被其他会议预约占用", secondResult.getMessage());
    }

    @Test
    @DisplayName("释放锁 - 成功获取锁后释放锁，应使会议室变为可获取状态")
    void releaseLock_ShouldMakeRoomAvailable_AfterSuccessfulAcquire() {
        String roomId = "room_001";
        String ownerId = "user_001";

        LockResult acquireResult = lockManagerService.acquireLock(roomId, ownerId, "regular");
        assertTrue(acquireResult.isAcquired());
        assertTrue(lockManagerService.isRoomLocked(roomId));

        lockManagerService.releaseLock(roomId, ownerId);
        assertFalse(lockManagerService.isRoomLocked(roomId));

        LockResult reAcquireResult = lockManagerService.acquireLock(roomId, "user_002", "regular");
        assertTrue(reAcquireResult.isAcquired());
    }

    @Test
    @DisplayName("紧急会议锁定超时 - 紧急会议应有更短的锁定超时时间")
    void getLockTimeout_ShouldBeShorter_ForUrgentMeetings() {
        long urgentTimeout = lockManagerService.getLockTimeoutByType("urgent");
        long regularTimeout = lockManagerService.getLockTimeoutByType("regular");

        assertEquals(LockManagerService.URGENT_LOCK_TIMEOUT_SECONDS, urgentTimeout);
        assertEquals(LockManagerService.REGULAR_LOCK_TIMEOUT_SECONDS, regularTimeout);
        assertTrue(urgentTimeout < regularTimeout, "紧急会议的锁定超时应短于普通会议");
    }

    @Test
    @DisplayName("并发预约锁冲突 - 多个线程同时请求同一会议室锁时，只有一个线程能获取成功")
    void acquireLock_ShouldHandleConcurrentRequests_Correctly() throws InterruptedException {
        String roomId = "room_concurrent_001";
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    LockResult result = lockManagerService.acquireLock(roomId, "user_" + index, "regular");
                    if (result.isAcquired()) {
                        successCount.incrementAndGet();
                        Thread.sleep(50);
                        lockManagerService.releaseLock(roomId, "user_" + index);
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
        endLatch.await();
        executor.shutdown();

        assertEquals(threadCount, successCount.get() + failCount.get(), "所有请求都应被处理");
        assertEquals(1, successCount.get(), "只有一个线程能成功获取锁");
    }

    @Test
    @DisplayName("会议室已占用拒绝处理 - 会议室状态为占用时拒绝预约")
    void isRoomLocked_ShouldReturnTrue_WhenRoomIsLocked() {
        String roomId = "room_occupied_001";

        assertFalse(lockManagerService.isRoomLocked(roomId));

        lockManagerService.acquireLock(roomId, "user_001", "regular");
        assertTrue(lockManagerService.isRoomLocked(roomId));
    }

    @Test
    @DisplayName("强制释放锁 - 应该能够强制释放已锁定的会议室")
    void forceReleaseLock_ShouldWork_WhenRoomIsLocked() {
        String roomId = "room_force_001";
        String ownerId = "user_001";

        lockManagerService.acquireLock(roomId, ownerId, "regular");
        assertTrue(lockManagerService.isRoomLocked(roomId));

        lockManagerService.forceReleaseLock(roomId);
        assertFalse(lockManagerService.isRoomLocked(roomId));
    }

    @Test
    @DisplayName("释放所有锁 - 应该能够批量释放所有锁")
    void releaseAllLocks_ShouldReleaseAll_WhenMultipleRoomsLocked() {
        String room1 = "room_batch_001";
        String room2 = "room_batch_002";
        String room3 = "room_batch_003";

        lockManagerService.acquireLock(room1, "user_001", "regular");
        lockManagerService.acquireLock(room2, "user_002", "regular");
        lockManagerService.acquireLock(room3, "user_003", "regular");

        assertTrue(lockManagerService.isRoomLocked(room1));
        assertTrue(lockManagerService.isRoomLocked(room2));
        assertTrue(lockManagerService.isRoomLocked(room3));

        lockManagerService.releaseAllLocks();

        assertFalse(lockManagerService.isRoomLocked(room1));
        assertFalse(lockManagerService.isRoomLocked(room2));
        assertFalse(lockManagerService.isRoomLocked(room3));
    }

    @Test
    @DisplayName("锁释放恢复 - 释放锁后其他请求应能正常获取锁")
    void acquireLock_ShouldSucceed_AfterPreviousLockReleased() {
        String roomId = "room_release_001";
        String firstOwner = "user_001";
        String secondOwner = "user_002";

        LockResult firstAcquire = lockManagerService.acquireLock(roomId, firstOwner, "regular");
        assertTrue(firstAcquire.isAcquired());

        LockResult conflictAcquire = lockManagerService.acquireLock(roomId, secondOwner, "regular");
        assertFalse(conflictAcquire.isAcquired());

        lockManagerService.releaseLock(roomId, firstOwner);

        LockResult successfulAcquire = lockManagerService.acquireLock(roomId, secondOwner, "regular");
        assertTrue(successfulAcquire.isAcquired());
        assertEquals(secondOwner, successfulAcquire.getLockOwner());
    }

    @Test
    @DisplayName("不同会议室独立锁 - 锁定一个会议室不应影响其他会议室")
    void acquireLock_ShouldBeIndependent_ForDifferentRooms() {
        String room1 = "room_independent_001";
        String room2 = "room_independent_002";
        String owner1 = "user_001";
        String owner2 = "user_002";

        LockResult result1 = lockManagerService.acquireLock(room1, owner1, "regular");
        LockResult result2 = lockManagerService.acquireLock(room2, owner2, "regular");

        assertTrue(result1.isAcquired());
        assertTrue(result2.isAcquired());
        assertTrue(lockManagerService.isRoomLocked(room1));
        assertTrue(lockManagerService.isRoomLocked(room2));
    }

    @Test
    @DisplayName("同一用户重复获取锁 - 同一用户不能重复获取同一会议室的锁")
    void acquireLock_ShouldFail_WhenSameOwnerReacquires() {
        String roomId = "room_same_owner_001";
        String ownerId = "user_001";

        LockResult firstResult = lockManagerService.acquireLock(roomId, ownerId, "regular");
        assertTrue(firstResult.isAcquired());

        LockResult secondResult = lockManagerService.acquireLock(roomId, ownerId, "regular");
        assertFalse(secondResult.isAcquired());
        assertEquals(ownerId, secondResult.getLockOwner());
    }

    @Test
    @DisplayName("普通会议锁定超时 - 普通会议应有标准的锁定超时时间")
    void acquireLock_RegularMeeting_ShouldHaveStandardTimeout() {
        long timeout = lockManagerService.getLockTimeoutByType("regular");
        assertEquals(60, timeout);
    }

    @Test
    @DisplayName("未锁定的会议室 - 调用释放锁不应抛异常")
    void releaseLock_ShouldNotThrow_WhenRoomNotLocked() {
        String roomId = "room_not_locked_001";
        String ownerId = "user_001";

        assertDoesNotThrow(() -> {
            lockManagerService.releaseLock(roomId, ownerId);
        });
        assertFalse(lockManagerService.isRoomLocked(roomId));
    }

    @Test
    @DisplayName("测试锁获取消息 - 成功获取锁应有正确的消息")
    void acquireLock_Success_ShouldHaveCorrectMessage() {
        String roomId = "room_msg_001";
        String ownerId = "user_001";

        LockResult result = lockManagerService.acquireLock(roomId, ownerId, "regular");

        assertEquals("锁获取成功", result.getMessage());
    }

    @Test
    @DisplayName("测试锁冲突消息 - 锁被占用时应有正确的冲突消息")
    void acquireLock_Conflict_ShouldHaveCorrectMessage() {
        String roomId = "room_msg_002";
        String firstOwner = "user_001";
        String secondOwner = "user_002";

        lockManagerService.acquireLock(roomId, firstOwner, "regular");
        LockResult result = lockManagerService.acquireLock(roomId, secondOwner, "regular");

        assertEquals("会议室正在被其他会议预约占用", result.getMessage());
    }
}
