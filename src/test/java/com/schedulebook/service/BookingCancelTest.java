package com.schedulebook.service;

import com.schedulebook.dto.CancelBookingRequest;
import com.schedulebook.exception.BookingException;
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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("预约取消测试 - 完整取消流程验证")
class BookingCancelTest {

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private DispatchService dispatchService;

    @Mock
    private ScheduleService scheduleService;

    @Mock
    private ReminderService reminderService;

    @Mock
    private StatisticsService statisticsService;

    @Mock
    private HistoryService historyService;

    @Mock
    private StatusTrackingService statusTrackingService;

    @Mock
    private CancelService cancelService;

    @Mock
    private IdGeneratorService idGeneratorService;

    @InjectMocks
    private BookingService bookingService;

    private Booking confirmedBooking;
    private Booking completedBooking;
    private Booking cancelledBooking;
    private CancelBookingRequest cancelRequest;
    private CancelRecord cancelRecord;
    private Dispatch assignedDispatch;
    private ScheduleSlot bookedSlot;
    private List<Reminder> pendingReminders;

    @BeforeEach
    void setUp() {
        confirmedBooking = TestDataBuilder.buildConfirmedBooking();
        completedBooking = TestDataBuilder.buildConfirmedBooking();
        completedBooking.setBookingStatus("completed");
        cancelledBooking = TestDataBuilder.buildConfirmedBooking();
        cancelledBooking.setBookingStatus("cancelled");

        cancelRequest = new CancelBookingRequest();
        cancelRequest.setBookingId("booking_001");
        cancelRequest.setCancelReason("时间冲突");
        cancelRequest.setCancelBy("user_10086");

        cancelRecord = TestDataBuilder.buildCancelRecord();
        assignedDispatch = TestDataBuilder.buildDispatch();
        bookedSlot = TestDataBuilder.buildBookedScheduleSlot(
                confirmedBooking.getBookingTime(), "booking_001");
        pendingReminders = TestDataBuilder.buildMultipleReminders("booking_001", 3);
    }

    @Test
    @DisplayName("测试预约取消 - 成功取消已确认预约")
    void testCancelBooking_Success() {
        when(bookingRepository.findByBookingId("booking_001"))
                .thenReturn(Optional.of(confirmedBooking));
        when(cancelService.processCancel(any(Booking.class), any(CancelBookingRequest.class)))
                .thenReturn(cancelRecord);
        when(bookingRepository.save(any(Booking.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> result = bookingService.cancelBooking(cancelRequest);

        assertNotNull(result, "取消结果不应该为空");
        assertEquals("cancel_001", result.get("cancel_id"));
        assertEquals("cancelled", result.get("status"));

        verify(dispatchService).releaseResource(confirmedBooking);
        verify(reminderService).cancelReminders("booking_001");
        verify(statusTrackingService).updateStatus("booking_001", "cancelled");
        verify(statisticsService).updateStatisticsOnCancel(confirmedBooking);
        verify(historyService).recordHistory(
                argThat(b -> "cancelled".equals(b.getBookingStatus())),
                eq("cancel"),
                anyString());
    }

    @Test
    @DisplayName("测试预约取消 - 取消后资源正确释放")
    void testCancelBooking_ResourceReleased() {
        when(bookingRepository.findByBookingId("booking_001"))
                .thenReturn(Optional.of(confirmedBooking));
        when(cancelService.processCancel(any(Booking.class), any(CancelBookingRequest.class)))
                .thenReturn(cancelRecord);
        when(bookingRepository.save(any(Booking.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        bookingService.cancelBooking(cancelRequest);

        verify(dispatchService, times(1)).releaseResource(
                argThat(b -> "booking_001".equals(b.getBookingId())));
    }

    @Test
    @DisplayName("测试预约取消 - 取消后排班状态恢复为可用")
    void testCancelBooking_SlotStatusRestored() {
        when(bookingRepository.findByBookingId("booking_001"))
                .thenReturn(Optional.of(confirmedBooking));
        when(cancelService.processCancel(any(Booking.class), any(CancelBookingRequest.class)))
                .thenReturn(cancelRecord);
        when(bookingRepository.save(any(Booking.class)))
                .thenAnswer(invocation -> {
                    Booking booking = invocation.getArgument(0);
                    assertEquals("cancelled", booking.getBookingStatus());
                    assertNotNull(booking.getCancelledAt());
                    return booking;
                });

        bookingService.cancelBooking(cancelRequest);

        verify(bookingRepository).save(argThat(booking -> 
                "cancelled".equals(booking.getBookingStatus()) && 
                booking.getCancelledAt() != null));
    }

    @Test
    @DisplayName("测试预约取消 - 取消后提醒配置被取消")
    void testCancelBooking_RemindersCancelled() {
        when(bookingRepository.findByBookingId("booking_001"))
                .thenReturn(Optional.of(confirmedBooking));
        when(cancelService.processCancel(any(Booking.class), any(CancelBookingRequest.class)))
                .thenReturn(cancelRecord);
        when(bookingRepository.save(any(Booking.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        bookingService.cancelBooking(cancelRequest);

        verify(reminderService, times(1)).cancelReminders("booking_001");
    }

    @Test
    @DisplayName("测试预约取消 - 取消历史记录完整性")
    void testCancelBooking_HistoryRecordComplete() {
        when(bookingRepository.findByBookingId("booking_001"))
                .thenReturn(Optional.of(confirmedBooking));
        when(cancelService.processCancel(any(Booking.class), any(CancelBookingRequest.class)))
                .thenReturn(cancelRecord);
        when(bookingRepository.save(any(Booking.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        bookingService.cancelBooking(cancelRequest);

        verify(historyService).recordHistory(
                any(Booking.class),
                eq("cancel"),
                argThat(detail -> detail != null && detail.contains("取消")));
    }

    @Test
    @DisplayName("测试预约取消 - 已完成的预约无法取消")
    void testCancelBooking_CompletedBookingCannotCancel() {
        when(bookingRepository.findByBookingId("booking_001"))
                .thenReturn(Optional.of(completedBooking));

        BookingException exception = assertThrows(BookingException.class, 
                () -> bookingService.cancelBooking(cancelRequest));

        assertEquals(400, exception.getCode());
        assertTrue(exception.getMessage().contains("已完成"));

        verify(dispatchService, never()).releaseResource(any(Booking.class));
        verify(reminderService, never()).cancelReminders(anyString());
    }

    @Test
    @DisplayName("测试预约取消 - 已取消的预约无法重复取消")
    void testCancelBooking_CancelledBookingCannotCancelAgain() {
        when(bookingRepository.findByBookingId("booking_001"))
                .thenReturn(Optional.of(cancelledBooking));

        BookingException exception = assertThrows(BookingException.class, 
                () -> bookingService.cancelBooking(cancelRequest));

        assertEquals(400, exception.getCode());
        assertTrue(exception.getMessage().contains("已经被取消"));
    }

    @Test
    @DisplayName("测试预约取消 - 不存在的预约无法取消")
    void testCancelBooking_NonExistentBooking() {
        when(bookingRepository.findByBookingId("booking_999"))
                .thenReturn(Optional.empty());

        cancelRequest.setBookingId("booking_999");
        BookingException exception = assertThrows(BookingException.class, 
                () -> bookingService.cancelBooking(cancelRequest));

        assertEquals(404, exception.getCode());
        assertTrue(exception.getMessage().contains("不存在"));
    }

    @Test
    @DisplayName("测试预约取消 - 取消后统计数据更新")
    void testCancelBooking_StatisticsUpdated() {
        when(bookingRepository.findByBookingId("booking_001"))
                .thenReturn(Optional.of(confirmedBooking));
        when(cancelService.processCancel(any(Booking.class), any(CancelBookingRequest.class)))
                .thenReturn(cancelRecord);
        when(bookingRepository.save(any(Booking.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        bookingService.cancelBooking(cancelRequest);

        verify(statisticsService, times(1)).updateStatisticsOnCancel(
                argThat(b -> "booking_001".equals(b.getBookingId())));
    }

    @Test
    @DisplayName("测试预约取消 - 取消后状态追踪更新")
    void testCancelBooking_StatusTrackingUpdated() {
        when(bookingRepository.findByBookingId("booking_001"))
                .thenReturn(Optional.of(confirmedBooking));
        when(cancelService.processCancel(any(Booking.class), any(CancelBookingRequest.class)))
                .thenReturn(cancelRecord);
        when(bookingRepository.save(any(Booking.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        bookingService.cancelBooking(cancelRequest);

        verify(statusTrackingService, times(1)).updateStatus(
                "booking_001", 
                "cancelled");
    }

    @Test
    @DisplayName("测试预约取消 - 取消记录包含正确信息")
    void testCancelBooking_CancelRecordCorrect() {
        when(bookingRepository.findByBookingId("booking_001"))
                .thenReturn(Optional.of(confirmedBooking));
        when(cancelService.processCancel(any(Booking.class), any(CancelBookingRequest.class)))
                .thenReturn(cancelRecord);
        when(bookingRepository.save(any(Booking.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> result = bookingService.cancelBooking(cancelRequest);

        assertEquals("cancel_001", result.get("cancel_id"));
        
        verify(cancelService).processCancel(
                argThat(b -> "booking_001".equals(b.getBookingId())),
                argThat(r -> "时间冲突".equals(r.getCancelReason())));
    }

    @Test
    @DisplayName("测试预约取消 - 取消后预约状态正确更新")
    void testCancelBooking_BookingStatusUpdated() {
        when(bookingRepository.findByBookingId("booking_001"))
                .thenReturn(Optional.of(confirmedBooking));
        when(cancelService.processCancel(any(Booking.class), any(CancelBookingRequest.class)))
                .thenReturn(cancelRecord);
        when(bookingRepository.save(any(Booking.class)))
                .thenAnswer(invocation -> {
                    Booking savedBooking = invocation.getArgument(0);
                    return savedBooking;
                });

        bookingService.cancelBooking(cancelRequest);

        verify(bookingRepository).save(argThat(booking -> 
                "cancelled".equals(booking.getBookingStatus())));
    }

    @Test
    @DisplayName("测试预约取消 - 取消后资源占用计数减少")
    void testCancelBooking_ResourceOccupancyDecreased() {
        when(bookingRepository.findByBookingId("booking_001"))
                .thenReturn(Optional.of(confirmedBooking));
        when(cancelService.processCancel(any(Booking.class), any(CancelBookingRequest.class)))
                .thenReturn(cancelRecord);
        when(bookingRepository.save(any(Booking.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        bookingService.cancelBooking(cancelRequest);

        verify(dispatchService).releaseResource(any(Booking.class));
    }

    @Test
    @DisplayName("测试预约取消 - 取消后排班时间段预约数减少")
    void testCancelBooking_SlotBookingsDecreased() {
        when(bookingRepository.findByBookingId("booking_001"))
                .thenReturn(Optional.of(confirmedBooking));
        when(cancelService.processCancel(any(Booking.class), any(CancelBookingRequest.class)))
                .thenReturn(cancelRecord);
        when(bookingRepository.save(any(Booking.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        bookingService.cancelBooking(cancelRequest);

        verify(dispatchService).releaseResource(
                argThat(b -> "booking_001".equals(b.getBookingId())));
    }
}
