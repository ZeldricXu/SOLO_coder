package com.schedulebook.service;

import com.schedulebook.model.*;
import com.schedulebook.repository.*;
import com.schedulebook.testdata.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
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
@DisplayName("调度模块测试 - 排班状态锁定机制")
class DispatchServiceTest {

    @Mock
    private DispatchRepository dispatchRepository;

    @Mock
    private ResourceRepository resourceRepository;

    @Mock
    private ScheduleRepository scheduleRepository;

    @Mock
    private ScheduleSlotRepository scheduleSlotRepository;

    @Mock
    private IdGeneratorService idGeneratorService;

    @InjectMocks
    private DispatchService dispatchService;

    private ScheduleLockService scheduleLockService;
    private Resource testResource;
    private Booking testBooking;
    private Schedule testSchedule;
    private ScheduleSlot testSlot;

    @BeforeEach
    void setUp() {
        scheduleLockService = new ScheduleLockService();
        scheduleLockService.clearAllLocks();
        
        testResource = TestDataBuilder.buildResource();
        testBooking = TestDataBuilder.buildBooking();
        testSchedule = TestDataBuilder.buildSchedule();
        testSlot = TestDataBuilder.buildScheduleSlot(LocalTime.of(10, 0), "available");
    }

    @Test
    @DisplayName("测试排班状态锁定 - 单用户获取锁定成功")
    void testScheduleLock_SingleUserAcquireLock() {
        String resourceId = "room_001";
        LocalDate date = LocalDate.of(2026, 5, 5);
        LocalTime time = LocalTime.of(10, 0);
        String bookingId = "booking_001";

        boolean locked = scheduleLockService.tryLock(resourceId, date, time, bookingId);

        assertTrue(locked, "应该成功获取锁定");
        assertTrue(scheduleLockService.isLocked(resourceId, date, time), "时间段应该被锁定");
        assertEquals(bookingId, scheduleLockService.getLockHolder(resourceId, date, time), 
                "锁定持有者应该是正确的预约");
    }

    @Test
    @DisplayName("测试排班状态锁定 - 多用户并发预约同一时间段")
    void testScheduleLock_MultipleUsersConcurrentBooking() throws InterruptedException {
        String resourceId = "room_001";
        LocalDate date = LocalDate.of(2026, 5, 5);
        LocalTime time = LocalTime.of(10, 0);
        
        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        List<String> successfulBookings = Collections.synchronizedList(new ArrayList<>());

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 1; i <= threadCount; i++) {
            final int bookingNum = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    String bookingId = "booking_" + bookingNum;
                    boolean locked = scheduleLockService.tryLock(resourceId, date, time, bookingId);
                    if (locked) {
                        successCount.incrementAndGet();
                        successfulBookings.add(bookingId);
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

        assertEquals(1, successCount.get(), "应该只有一个用户成功获取锁定");
        assertEquals(9, failCount.get(), "其他9个用户应该获取锁定失败");
        assertEquals(1, scheduleLockService.getActiveLockCount(), "应该只有一个活动锁定");
        
        scheduleLockService.releaseLock(resourceId, date, time, successfulBookings.get(0));
        assertEquals(0, scheduleLockService.getActiveLockCount(), "释放锁定后活动锁定数应该为0");
    }

    @Test
    @DisplayName("测试锁定获取与释放的时序")
    void testScheduleLock_AcquireAndReleaseSequence() {
        String resourceId = "room_001";
        LocalDate date = LocalDate.of(2026, 5, 5);
        LocalTime time = LocalTime.of(10, 0);
        String bookingId1 = "booking_001";
        String bookingId2 = "booking_002";

        boolean locked1 = scheduleLockService.tryLock(resourceId, date, time, bookingId1);
        assertTrue(locked1, "第一个用户应该成功获取锁定");

        boolean locked2 = scheduleLockService.tryLock(resourceId, date, time, bookingId2);
        assertFalse(locked2, "第二个用户在锁定未释放时应该获取失败");

        scheduleLockService.releaseLock(resourceId, date, time, bookingId1);
        assertFalse(scheduleLockService.isLocked(resourceId, date, time), "锁定应该被释放");

        boolean locked2AfterRelease = scheduleLockService.tryLock(resourceId, date, time, bookingId2);
        assertTrue(locked2AfterRelease, "第二个用户在锁定释放后应该可以获取锁定");

        scheduleLockService.releaseLock(resourceId, date, time, bookingId2);
    }

    @Test
    @DisplayName("测试并发预约下不出现重复预约")
    void testScheduleLock_NoDuplicateBookingsInConcurrency() throws InterruptedException {
        String resourceId = "room_001";
        LocalDate date = LocalDate.of(2026, 5, 5);
        LocalTime time = LocalTime.of(10, 0);
        
        int threadCount = 50;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        Set<String> successfulBookings = Collections.synchronizedSet(new HashSet<>());

        ExecutorService executor = Executors.newFixedThreadPool(20);

        for (int i = 1; i <= threadCount; i++) {
            final int bookingNum = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    String bookingId = "booking_" + bookingNum;
                    boolean locked = scheduleLockService.tryLock(resourceId, date, time, bookingId);
                    if (locked) {
                        successfulBookings.add(bookingId);
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

        assertEquals(1, successfulBookings.size(), 
                "应该只有一个预约成功，不应该出现重复预约");
    }

    @Test
    @DisplayName("测试不同紧急程度预约的锁定超时差异 - 高紧急程度")
    void testScheduleLock_HighUrgencyLockTimeout() {
        String resourceId = "room_001";
        LocalDate date = LocalDate.of(2026, 5, 5);
        LocalTime time = LocalTime.of(10, 0);
        String bookingId = "booking_urgent";

        scheduleLockService.tryLock(resourceId, date, time, bookingId, "high");
        
        long remainingTime = scheduleLockService.getLockRemainingTime(resourceId, date, time);
        assertTrue(remainingTime > 50000, "高紧急程度锁定剩余时间应该大于50秒");
    }

    @Test
    @DisplayName("测试不同紧急程度预约的锁定超时差异 - 低紧急程度")
    void testScheduleLock_LowUrgencyLockTimeout() {
        String resourceId = "room_002";
        LocalDate date = LocalDate.of(2026, 5, 5);
        LocalTime time = LocalTime.of(11, 0);
        String bookingId = "booking_normal";

        scheduleLockService.tryLock(resourceId, date, time, bookingId, "low");
        
        long remainingTime = scheduleLockService.getLockRemainingTime(resourceId, date, time);
        assertTrue(remainingTime < 20000, "低紧急程度锁定剩余时间应该小于20秒");
    }

    @Test
    @DisplayName("测试同一预约重复获取锁定")
    void testScheduleLock_SameBookingReacquireLock() {
        String resourceId = "room_001";
        LocalDate date = LocalDate.of(2026, 5, 5);
        LocalTime time = LocalTime.of(10, 0);
        String bookingId = "booking_001";

        boolean firstLock = scheduleLockService.tryLock(resourceId, date, time, bookingId);
        assertTrue(firstLock, "第一次获取锁定应该成功");

        boolean secondLock = scheduleLockService.tryLock(resourceId, date, time, bookingId);
        assertTrue(secondLock, "同一预约重复获取锁定应该成功");

        assertEquals(1, scheduleLockService.getActiveLockCount(), 
                "活动锁定数应该保持为1，不应该增加");
    }

    @Test
    @DisplayName("测试错误的预约释放锁定")
    void testScheduleLock_WrongBookingReleaseLock() {
        String resourceId = "room_001";
        LocalDate date = LocalDate.of(2026, 5, 5);
        LocalTime time = LocalTime.of(10, 0);
        String bookingId1 = "booking_001";
        String bookingId2 = "booking_002";

        scheduleLockService.tryLock(resourceId, date, time, bookingId1);
        
        scheduleLockService.releaseLock(resourceId, date, time, bookingId2);
        
        assertTrue(scheduleLockService.isLocked(resourceId, date, time), 
                "错误的预约不应该能释放别人的锁定");
        assertEquals(bookingId1, scheduleLockService.getLockHolder(resourceId, date, time), 
                "锁定持有者应该保持不变");
    }

    @Test
    @DisplayName("测试调度资源分配 - 资源可用时分配成功")
    void testDispatchService_AllocateResource_Success() {
        when(resourceRepository.findAvailableResourcesByType("room")).thenReturn(List.of(testResource));
        when(scheduleRepository.findByResourceIdAndScheduleDate(
                testResource.getResourceId(), testBooking.getBookingDate()))
                .thenReturn(Optional.of(testSchedule));
        when(scheduleSlotRepository.findByScheduleIdAndSlotTime(
                testSchedule.getId(), testBooking.getBookingTime()))
                .thenReturn(Optional.of(testSlot));
        when(idGeneratorService.generateDispatchId()).thenReturn("dispatch_001");
        when(dispatchRepository.save(any(Dispatch.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(scheduleSlotRepository.save(any(ScheduleSlot.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(resourceRepository.save(any(Resource.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Dispatch dispatch = dispatchService.allocateResource(testBooking, null);

        assertNotNull(dispatch, "调度记录应该被创建");
        assertEquals("assigned", dispatch.getDispatchStatus(), "调度状态应该是已分配");
        assertEquals(testResource.getResourceId(), dispatch.getResourceId(), "应该分配正确的资源");
    }

    @Test
    @DisplayName("测试调度资源分配 - 资源不可用时返回null")
    void testDispatchService_AllocateResource_Unavailable() {
        when(resourceRepository.findAvailableResourcesByType("room")).thenReturn(List.of(testResource));
        when(scheduleRepository.findByResourceIdAndScheduleDate(
                testResource.getResourceId(), testBooking.getBookingDate()))
                .thenReturn(Optional.empty());

        Dispatch dispatch = dispatchService.allocateResource(testBooking, null);

        assertNull(dispatch, "没有可用排班时应该返回null");
    }

    @Test
    @DisplayName("测试调度资源分配 - 时间段已被预约时返回null")
    void testDispatchService_AllocateResource_SlotAlreadyBooked() {
        ScheduleSlot bookedSlot = TestDataBuilder.buildBookedScheduleSlot(
                LocalTime.of(10, 0), "booking_another");
        
        when(resourceRepository.findAvailableResourcesByType("room")).thenReturn(List.of(testResource));
        when(scheduleRepository.findByResourceIdAndScheduleDate(
                testResource.getResourceId(), testBooking.getBookingDate()))
                .thenReturn(Optional.of(testSchedule));
        when(scheduleSlotRepository.findByScheduleIdAndSlotTime(
                testSchedule.getId(), testBooking.getBookingTime()))
                .thenReturn(Optional.of(bookedSlot));

        Dispatch dispatch = dispatchService.allocateResource(testBooking, null);

        assertNull(dispatch, "时间段已被预约时应该返回null");
    }

    @Test
    @DisplayName("测试资源释放 - 成功释放调度资源")
    void testDispatchService_ReleaseResource_Success() {
        Dispatch assignedDispatch = TestDataBuilder.buildDispatch();
        assignedDispatch.setDispatchStatus("assigned");
        
        when(dispatchRepository.findByBookingIdAndDispatchStatus(
                testBooking.getBookingId(), "assigned"))
                .thenReturn(Optional.of(assignedDispatch));
        when(resourceRepository.findByResourceId(assignedDispatch.getResourceId()))
                .thenReturn(Optional.of(testResource));
        when(scheduleRepository.findByResourceIdAndScheduleDate(
                assignedDispatch.getResourceId(), testBooking.getBookingDate()))
                .thenReturn(Optional.of(testSchedule));
        when(scheduleSlotRepository.findByScheduleIdAndSlotTime(
                testSchedule.getId(), testBooking.getBookingTime()))
                .thenReturn(Optional.of(testSlot));

        assertDoesNotThrow(() -> dispatchService.releaseResource(testBooking), 
                "释放资源不应该抛出异常");
    }
}
