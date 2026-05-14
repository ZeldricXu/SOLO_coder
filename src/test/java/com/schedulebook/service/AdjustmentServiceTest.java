package com.schedulebook.service;

import com.schedulebook.exception.BookingException;
import com.schedulebook.model.Booking;
import com.schedulebook.model.Schedule;
import com.schedulebook.model.ScheduleSlot;
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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("排班调整测试 - 冲突检测机制")
class AdjustmentServiceTest {

    @Mock
    private ScheduleRepository scheduleRepository;

    @Mock
    private ScheduleSlotRepository scheduleSlotRepository;

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private AdjustmentService adjustmentService;

    private Schedule testSchedule;
    private Booking testBooking;
    private ScheduleSlot availableSlot;
    private ScheduleSlot bookedSlot;

    @BeforeEach
    void setUp() {
        testSchedule = TestDataBuilder.buildSchedule();
        testBooking = TestDataBuilder.buildConfirmedBooking();
        availableSlot = TestDataBuilder.buildScheduleSlot(LocalTime.of(10, 0), "available");
        bookedSlot = TestDataBuilder.buildBookedScheduleSlot(LocalTime.of(10, 0), "booking_another");
        adjustmentService.clearConflictDetectionCache();
    }

    @Test
    @DisplayName("测试排班调整冲突检测 - 无冲突时检测通过")
    void testConflictDetection_NoConflict() {
        when(scheduleRepository.findByScheduleId("schedule_room_001"))
                .thenReturn(Optional.of(testSchedule));
        when(scheduleSlotRepository.findByScheduleIdAndSlotTime(
                testSchedule.getId(), LocalTime.of(10, 0)))
                .thenReturn(Optional.of(availableSlot));
        when(scheduleSlotRepository.findByScheduleIdAndSlotTime(
                testSchedule.getId(), LocalTime.of(11, 0)))
                .thenReturn(Optional.empty());

        AdjustmentService.ConflictDetectionResult result = 
                adjustmentService.checkScheduleAdjustmentConflict(
                        "schedule_room_001", 
                        LocalTime.of(10, 0), 
                        LocalTime.of(11, 0));

        assertTrue(result.hasNoConflict(), "没有冲突时应该返回无冲突");
        assertFalse(result.hasConflict, "没有冲突时hasConflict应该为false");
        assertNull(result.conflictReason, "没有冲突时冲突原因为空");
    }

    @Test
    @DisplayName("测试排班调整冲突检测 - 原时间段已被预约")
    void testConflictDetection_SourceSlotBooked() {
        when(scheduleRepository.findByScheduleId("schedule_room_001"))
                .thenReturn(Optional.of(testSchedule));
        when(scheduleSlotRepository.findByScheduleIdAndSlotTime(
                testSchedule.getId(), LocalTime.of(10, 0)))
                .thenReturn(Optional.of(bookedSlot));

        AdjustmentService.ConflictDetectionResult result = 
                adjustmentService.checkScheduleAdjustmentConflict(
                        "schedule_room_001", 
                        LocalTime.of(10, 0), 
                        LocalTime.of(11, 0));

        assertTrue(result.hasConflict, "原时间段已被预约应该检测到冲突");
        assertNotNull(result.conflictReason, "应该有冲突原因");
        assertEquals("该时间段已被预约，无法调整", result.conflictReason);
        assertTrue(result.conflictingBookings.contains("booking_another"), 
                "应该包含冲突的预约ID");
    }

    @Test
    @DisplayName("测试排班调整冲突检测 - 目标时间段已被预约")
    void testConflictDetection_TargetSlotBooked() {
        ScheduleSlot targetBookedSlot = TestDataBuilder.buildBookedScheduleSlot(
                LocalTime.of(11, 0), "booking_conflict");
        
        when(scheduleRepository.findByScheduleId("schedule_room_001"))
                .thenReturn(Optional.of(testSchedule));
        when(scheduleSlotRepository.findByScheduleIdAndSlotTime(
                testSchedule.getId(), LocalTime.of(10, 0)))
                .thenReturn(Optional.of(availableSlot));
        when(scheduleSlotRepository.findByScheduleIdAndSlotTime(
                testSchedule.getId(), LocalTime.of(11, 0)))
                .thenReturn(Optional.of(targetBookedSlot));

        AdjustmentService.ConflictDetectionResult result = 
                adjustmentService.checkScheduleAdjustmentConflict(
                        "schedule_room_001", 
                        LocalTime.of(10, 0), 
                        LocalTime.of(11, 0));

        assertTrue(result.hasConflict, "目标时间段已被预约应该检测到冲突");
        assertTrue(result.conflictReason.contains("已被预约"));
        assertTrue(result.conflictingBookings.contains("booking_conflict"));
    }

    @Test
    @DisplayName("测试预约调整冲突检测 - 新时间段可用时无冲突")
    void testBookingConflictDetection_AvailableSlot() {
        Schedule newDateSchedule = TestDataBuilder.buildSchedule(
                "schedule_new", "room_001", LocalDate.of(2026, 5, 6));
        ScheduleSlot newAvailableSlot = TestDataBuilder.buildScheduleSlot(
                LocalTime.of(14, 0), "available");
        
        when(bookingRepository.findByBookingId("booking_001"))
                .thenReturn(Optional.of(testBooking));
        when(scheduleRepository.findByResourceIdAndScheduleDate(
                "room_001", LocalDate.of(2026, 5, 6)))
                .thenReturn(Optional.of(newDateSchedule));
        when(scheduleSlotRepository.findByScheduleIdAndSlotTime(
                newDateSchedule.getId(), LocalTime.of(14, 0)))
                .thenReturn(Optional.of(newAvailableSlot));

        AdjustmentService.ConflictDetectionResult result = 
                adjustmentService.checkBookingAdjustmentConflict(
                        "booking_001", 
                        LocalDate.of(2026, 5, 6), 
                        LocalTime.of(14, 0));

        assertTrue(result.hasNoConflict(), "新时间段可用时应该无冲突");
    }

    @Test
    @DisplayName("测试预约调整冲突检测 - 新时间段已被占用")
    void testBookingConflictDetection_OccupiedSlot() {
        Schedule newDateSchedule = TestDataBuilder.buildSchedule(
                "schedule_new", "room_001", LocalDate.of(2026, 5, 6));
        ScheduleSlot occupiedSlot = TestDataBuilder.buildBookedScheduleSlot(
                LocalTime.of(14, 0), "booking_existing");
        
        when(bookingRepository.findByBookingId("booking_001"))
                .thenReturn(Optional.of(testBooking));
        when(scheduleRepository.findByResourceIdAndScheduleDate(
                "room_001", LocalDate.of(2026, 5, 6)))
                .thenReturn(Optional.of(newDateSchedule));
        when(scheduleSlotRepository.findByScheduleIdAndSlotTime(
                newDateSchedule.getId(), LocalTime.of(14, 0)))
                .thenReturn(Optional.of(occupiedSlot));

        AdjustmentService.ConflictDetectionResult result = 
                adjustmentService.checkBookingAdjustmentConflict(
                        "booking_001", 
                        LocalDate.of(2026, 5, 6), 
                        LocalTime.of(14, 0));

        assertTrue(result.hasConflict, "新时间段已被占用应该检测到冲突");
        assertTrue(result.conflictingBookings.contains("booking_existing"));
    }

    @Test
    @DisplayName("测试预约调整冲突检测 - 预约状态不是已确认")
    void testBookingConflictDetection_NotConfirmed() {
        Booking pendingBooking = TestDataBuilder.buildBooking();
        pendingBooking.setBookingStatus("pending");
        
        when(bookingRepository.findByBookingId("booking_001"))
                .thenReturn(Optional.of(pendingBooking));

        AdjustmentService.ConflictDetectionResult result = 
                adjustmentService.checkBookingAdjustmentConflict(
                        "booking_001", 
                        LocalDate.of(2026, 5, 6), 
                        LocalTime.of(14, 0));

        assertTrue(result.hasConflict, "未确认的预约不能调整");
        assertTrue(result.conflictReason.contains("已确认"));
    }

    @Test
    @DisplayName("测试冲突检测异步化机制")
    void testAsyncConflictDetection() throws ExecutionException, InterruptedException {
        when(bookingRepository.findByBookingId("booking_001"))
                .thenReturn(Optional.of(testBooking));
        when(scheduleRepository.findByResourceIdAndScheduleDate(
                anyString(), any(LocalDate.class)))
                .thenReturn(Optional.of(testSchedule));
        when(scheduleSlotRepository.findByScheduleIdAndSlotTime(
                anyLong(), any(LocalTime.class)))
                .thenReturn(Optional.of(availableSlot));

        CompletableFuture<AdjustmentService.ConflictDetectionResult> future = 
                adjustmentService.checkBookingAdjustmentConflictAsync(
                        "booking_001", 
                        LocalDate.of(2026, 5, 6), 
                        LocalTime.of(10, 0));

        AdjustmentService.ConflictDetectionResult result = future.get();
        
        assertNotNull(result, "异步检测应该返回结果");
    }

    @Test
    @DisplayName("测试检测失败时的处理逻辑 - 抛出异常")
    void testDetectionFailure_ThrowsException() {
        when(scheduleRepository.findByScheduleId("schedule_room_001"))
                .thenReturn(Optional.of(testSchedule));
        when(scheduleSlotRepository.findByScheduleIdAndSlotTime(
                testSchedule.getId(), LocalTime.of(10, 0)))
                .thenReturn(Optional.of(bookedSlot));

        assertThrows(BookingException.class, () -> 
                adjustmentService.adjustScheduleTime(
                        "schedule_room_001", 
                        LocalTime.of(10, 0), 
                        LocalTime.of(11, 0)),
                "检测到冲突时应该抛出异常");
    }

    @Test
    @DisplayName("测试排班调整 - 无冲突时调整成功")
    void testScheduleAdjustment_Success() {
        ScheduleSlot newSlot = TestDataBuilder.buildScheduleSlot(
                LocalTime.of(11, 0), "available");
        
        when(scheduleRepository.findByScheduleId("schedule_room_001"))
                .thenReturn(Optional.of(testSchedule));
        when(scheduleSlotRepository.findByScheduleIdAndSlotTime(
                testSchedule.getId(), LocalTime.of(10, 0)))
                .thenReturn(Optional.of(availableSlot));
        when(scheduleSlotRepository.findByScheduleIdAndSlotTime(
                testSchedule.getId(), LocalTime.of(11, 0)))
                .thenReturn(Optional.of(newSlot));
        when(scheduleSlotRepository.save(any(ScheduleSlot.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(scheduleRepository.save(any(Schedule.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertDoesNotThrow(() -> 
                adjustmentService.adjustScheduleTime(
                        "schedule_room_001", 
                        LocalTime.of(10, 0), 
                        LocalTime.of(11, 0)));
        
        verify(scheduleSlotRepository).save(argThat(slot -> 
                slot.getSlotTime().equals(LocalTime.of(11, 0))));
    }

    @Test
    @DisplayName("测试冲突检测缓存机制")
    void testConflictDetection_CacheMechanism() {
        when(scheduleRepository.findByScheduleId("schedule_room_001"))
                .thenReturn(Optional.of(testSchedule));
        when(scheduleSlotRepository.findByScheduleIdAndSlotTime(
                testSchedule.getId(), LocalTime.of(10, 0)))
                .thenReturn(Optional.of(availableSlot));
        when(scheduleSlotRepository.findByScheduleIdAndSlotTime(
                testSchedule.getId(), LocalTime.of(11, 0)))
                .thenReturn(Optional.empty());

        AdjustmentService.ConflictDetectionResult result1 = 
                adjustmentService.checkScheduleAdjustmentConflict(
                        "schedule_room_001", 
                        LocalTime.of(10, 0), 
                        LocalTime.of(11, 0));

        AdjustmentService.ConflictDetectionResult result2 = 
                adjustmentService.checkScheduleAdjustmentConflict(
                        "schedule_room_001", 
                        LocalTime.of(10, 0), 
                        LocalTime.of(11, 0));

        verify(scheduleRepository, times(1)).findByScheduleId("schedule_room_001");
    }

    @Test
    @DisplayName("测试冲突检测结果包含冲突预约列表")
    void testConflictDetection_ConflictingBookingsList() {
        when(scheduleRepository.findByScheduleId("schedule_room_001"))
                .thenReturn(Optional.of(testSchedule));
        when(scheduleSlotRepository.findByScheduleIdAndSlotTime(
                testSchedule.getId(), LocalTime.of(10, 0)))
                .thenReturn(Optional.of(bookedSlot));

        AdjustmentService.ConflictDetectionResult result = 
                adjustmentService.checkScheduleAdjustmentConflict(
                        "schedule_room_001", 
                        LocalTime.of(10, 0), 
                        LocalTime.of(11, 0));

        assertNotNull(result.conflictingBookings, "冲突预约列表不应该为空");
        assertEquals(1, result.conflictingBookings.size(), "应该有一个冲突的预约");
        assertEquals("booking_another", result.conflictingBookings.get(0));
    }

    @Test
    @DisplayName("测试预约调整 - 原时间段状态恢复为可用")
    void testBookingAdjustment_OldSlotReleased() {
        Schedule oldSchedule = TestDataBuilder.buildSchedule();
        ScheduleSlot oldSlot = TestDataBuilder.buildBookedScheduleSlot(
                LocalTime.of(10, 0), "booking_001");
        
        Schedule newSchedule = TestDataBuilder.buildSchedule(
                "schedule_new", "room_001", LocalDate.of(2026, 5, 6));
        ScheduleSlot newSlot = TestDataBuilder.buildScheduleSlot(
                LocalTime.of(14, 0), "available");
        
        when(bookingRepository.findByBookingId("booking_001"))
                .thenReturn(Optional.of(testBooking));
        when(scheduleRepository.findByResourceIdAndScheduleDate(
                "room_001", LocalDate.of(2026, 5, 6)))
                .thenReturn(Optional.of(newSchedule));
        when(scheduleSlotRepository.findByScheduleIdAndSlotTime(
                newSchedule.getId(), LocalTime.of(14, 0)))
                .thenReturn(Optional.of(newSlot));
        when(scheduleRepository.findByResourceIdAndScheduleDate(
                "room_001", testBooking.getBookingDate()))
                .thenReturn(Optional.of(oldSchedule));
        when(scheduleSlotRepository.findByScheduleIdAndSlotTime(
                oldSchedule.getId(), testBooking.getBookingTime()))
                .thenReturn(Optional.of(oldSlot));
        when(scheduleSlotRepository.save(any(ScheduleSlot.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(bookingRepository.save(any(Booking.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        adjustmentService.adjustBookingTime(
                "booking_001", 
                LocalDate.of(2026, 5, 6), 
                LocalTime.of(14, 0));

        verify(scheduleSlotRepository).save(argThat(slot -> 
                "available".equals(slot.getSlotStatus()) && 
                slot.getCurrentBookings() == 0));
    }
}
