package com.hotelbooking.service;

import com.hotelbooking.builder.TestDataBuilder;
import com.hotelbooking.dto.BookingCreateRequest;
import com.hotelbooking.model.Booking;
import com.hotelbooking.model.Hotel;
import com.hotelbooking.model.Room;
import com.hotelbooking.repository.BookingRepository;
import com.hotelbooking.repository.HotelRepository;
import com.hotelbooking.repository.RoomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BookingServiceTest {

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private HotelRepository hotelRepository;

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private RoomService roomService;

    @Mock
    private AnalysisService analysisService;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private BookingService bookingService;

    private TestDataBuilder testDataBuilder;
    private Hotel testHotel;
    private Room testRoom;
    private LocalDate checkInDate;
    private LocalDate checkOutDate;

    @BeforeEach
    void setUp() {
        testDataBuilder = new TestDataBuilder();
        testHotel = testDataBuilder.buildActiveHotel();
        testRoom = testDataBuilder.buildAvailableRoom(testHotel);
        checkInDate = testDataBuilder.tomorrow();
        checkOutDate = testDataBuilder.daysFromNow(3);

        when(hotelRepository.findById(testHotel.getHotelId())).thenReturn(Optional.of(testHotel));
        when(roomRepository.findByIdForUpdate(testRoom.getRoomId())).thenReturn(Optional.of(testRoom));
        when(roomService.isRoomAvailable(testRoom.getRoomId(), checkInDate, checkOutDate)).thenReturn(true);
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("测试房间锁定机制 - 成功获取锁并创建预订")
    void testRoomLocking_SuccessfulLockAndCreateBooking() {
        BookingCreateRequest request = testDataBuilder.buildBookingCreateRequest(
                testHotel.getHotelId(),
                testRoom.getRoomId(),
                checkInDate,
                checkOutDate
        );

        Booking result = bookingService.createBooking(request);

        assertNotNull(result);
        assertEquals(testHotel.getHotelId(), result.getHotelId());
        assertEquals(testRoom.getRoomId(), result.getRoomId());
        assertEquals("pending", result.getBookingStatus());
        assertNotNull(result.getBookingId());

        verify(roomRepository, times(1)).findByIdForUpdate(testRoom.getRoomId());
        verify(bookingRepository, times(1)).save(any(Booking.class));
        verify(analysisService, times(1)).incrementBookingCount(testHotel.getHotelId());
    }

    @Test
    @DisplayName("测试房间锁定机制 - 酒店已关闭时拒绝预订")
    void testRoomLocking_HotelClosed_ShouldThrowException() {
        Hotel inactiveHotel = testDataBuilder.buildInactiveHotel();
        when(hotelRepository.findById(inactiveHotel.getHotelId())).thenReturn(Optional.of(inactiveHotel));

        BookingCreateRequest request = testDataBuilder.buildBookingCreateRequest(
                inactiveHotel.getHotelId(),
                testRoom.getRoomId(),
                checkInDate,
                checkOutDate
        );

        RuntimeException exception = assertThrows(RuntimeException.class, 
                () -> bookingService.createBooking(request));
        
        assertTrue(exception.getMessage().contains("酒店已关闭"));
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    @Test
    @DisplayName("测试房间锁定机制 - 房间不存在时抛出异常")
    void testRoomLocking_RoomNotFound_ShouldThrowException() {
        String nonExistentRoomId = "room_nonexistent";
        when(roomRepository.findByIdForUpdate(nonExistentRoomId)).thenReturn(Optional.empty());

        BookingCreateRequest request = testDataBuilder.buildBookingCreateRequest(
                testHotel.getHotelId(),
                nonExistentRoomId,
                checkInDate,
                checkOutDate
        );

        RuntimeException exception = assertThrows(RuntimeException.class, 
                () -> bookingService.createBooking(request));
        
        assertTrue(exception.getMessage().contains("房间不存在"));
    }

    @Test
    @DisplayName("测试并发预订时锁冲突处理 - 房间被锁定时第二个预订应失败")
    void testConcurrentBooking_LockConflict_SecondBookingShouldFail() {
        when(roomService.isRoomAvailable(testRoom.getRoomId(), checkInDate, checkOutDate))
                .thenReturn(true)
                .thenReturn(false);

        BookingCreateRequest request1 = testDataBuilder.buildBookingCreateRequest(
                testHotel.getHotelId(),
                testRoom.getRoomId(),
                checkInDate,
                checkOutDate
        );

        BookingCreateRequest request2 = testDataBuilder.buildBookingCreateRequest(
                testHotel.getHotelId(),
                testRoom.getRoomId(),
                checkInDate,
                checkOutDate
        );

        Booking booking1 = bookingService.createBooking(request1);
        assertNotNull(booking1);

        RuntimeException exception = assertThrows(RuntimeException.class, 
                () -> bookingService.createBooking(request2));
        
        assertTrue(exception.getMessage().contains("已被预订"));
    }

    @Test
    @DisplayName("测试不同客户等级锁定超时差异 - VIP客户短超时")
    void testLockTimeout_DifferentCustomerLevels() {
        assertEquals(3000, LockService.CustomerLevel.VIP.getTimeoutMillis());
        assertEquals(10000, LockService.CustomerLevel.NORMAL.getTimeoutMillis());
        
        assertTrue(LockService.CustomerLevel.VIP.getTimeoutMillis() < 
                   LockService.CustomerLevel.NORMAL.getTimeoutMillis());
    }

    @Test
    @DisplayName("测试锁定释放时序 - 取消预订后释放房间锁")
    void testLockReleaseSequence_CancelBookingShouldReleaseRoom() {
        Booking booking = testDataBuilder.buildConfirmedBooking(testHotel, testRoom, checkInDate, checkOutDate);
        when(bookingRepository.findById(booking.getBookingId())).thenReturn(Optional.of(booking));
        when(roomRepository.findByIdForUpdate(testRoom.getRoomId())).thenReturn(Optional.of(testRoom));

        Booking result = bookingService.cancelBooking(booking.getBookingId());

        assertEquals("cancelled", result.getBookingStatus());
        verify(roomRepository, times(1)).findByIdForUpdate(testRoom.getRoomId());
        verify(roomRepository).save(argThat(room -> "available".equals(room.getRoomStatus())));
    }

    @Test
    @DisplayName("测试预订时间冲突检测 - 日期完全重叠时拒绝预订")
    void testBookingConflictDetection_FullOverlap_ShouldReject() {
        when(roomService.isRoomAvailable(testRoom.getRoomId(), checkInDate, checkOutDate)).thenReturn(false);
        when(bookingRepository.findConflictingBookings(eq(testRoom.getRoomId()), eq(checkInDate), eq(checkOutDate)))
                .thenReturn(List.of(testDataBuilder.buildConfirmedBooking(testHotel, testRoom, checkInDate, checkOutDate)));

        BookingCreateRequest request = testDataBuilder.buildBookingCreateRequest(
                testHotel.getHotelId(),
                testRoom.getRoomId(),
                checkInDate,
                checkOutDate
        );

        RuntimeException exception = assertThrows(RuntimeException.class, 
                () -> bookingService.createBooking(request));
        
        assertTrue(exception.getMessage().contains("已被预订"));
    }

    @Test
    @DisplayName("测试预订时间冲突检测 - 部分重叠时拒绝预订")
    void testBookingConflictDetection_PartialOverlap_ShouldReject() {
        Room room = testDataBuilder.buildAvailableRoom(testHotel);
        
        Booking existingBooking = testDataBuilder.buildConfirmedBooking(
                testHotel, room, 
                testDataBuilder.daysFromNow(1), 
                testDataBuilder.daysFromNow(4)
        );
        
        when(bookingRepository.findById(testHotel.getHotelId())).thenReturn(Optional.of(testHotel));
        when(roomRepository.findByIdForUpdate(room.getRoomId())).thenReturn(Optional.of(room));
        when(roomService.isRoomAvailable(room.getRoomId(), testDataBuilder.daysFromNow(2), testDataBuilder.daysFromNow(5)))
                .thenReturn(false);

        BookingCreateRequest request = testDataBuilder.buildBookingCreateRequest(
                testHotel.getHotelId(),
                room.getRoomId(),
                testDataBuilder.daysFromNow(2),
                testDataBuilder.daysFromNow(5)
        );

        RuntimeException exception = assertThrows(RuntimeException.class, 
                () -> bookingService.createBooking(request));
        
        assertTrue(exception.getMessage().contains("已被预订"));
    }

    @Test
    @DisplayName("测试预订时间冲突检测 - 无冲突时允许预订")
    void testBookingConflictDetection_NoConflict_ShouldAllow() {
        Room room = testDataBuilder.buildAvailableRoom(testHotel);
        when(hotelRepository.findById(testHotel.getHotelId())).thenReturn(Optional.of(testHotel));
        when(roomRepository.findByIdForUpdate(room.getRoomId())).thenReturn(Optional.of(room));
        when(roomService.isRoomAvailable(room.getRoomId(), checkInDate, checkOutDate)).thenReturn(true);

        BookingCreateRequest request = testDataBuilder.buildBookingCreateRequest(
                testHotel.getHotelId(),
                room.getRoomId(),
                checkInDate,
                checkOutDate
        );

        Booking result = bookingService.createBooking(request);

        assertNotNull(result);
        assertEquals("pending", result.getBookingStatus());
    }

    @Test
    @DisplayName("测试预订确认 - 确认成功后更新房间状态")
    void testConfirmBooking_Successful_ShouldUpdateRoomStatus() {
        Booking pendingBooking = testDataBuilder.buildPendingBooking(testHotel, testRoom, checkInDate, checkOutDate);
        when(bookingRepository.findById(pendingBooking.getBookingId())).thenReturn(Optional.of(pendingBooking));
        when(roomRepository.findByIdForUpdate(testRoom.getRoomId())).thenReturn(Optional.of(testRoom));

        Booking result = bookingService.confirmBooking(pendingBooking.getBookingId());

        assertEquals("confirmed", result.getBookingStatus());
        verify(roomRepository).save(argThat(room -> "booked".equals(room.getRoomStatus())));
    }

    @Test
    @DisplayName("测试预订确认 - 已确认的预订再次确认应失败")
    void testConfirmBooking_AlreadyConfirmed_ShouldThrowException() {
        Booking confirmedBooking = testDataBuilder.buildConfirmedBooking(testHotel, testRoom, checkInDate, checkOutDate);
        when(bookingRepository.findById(confirmedBooking.getBookingId())).thenReturn(Optional.of(confirmedBooking));

        RuntimeException exception = assertThrows(RuntimeException.class, 
                () -> bookingService.confirmBooking(confirmedBooking.getBookingId()));
        
        assertTrue(exception.getMessage().contains("状态不允许确认"));
    }

    @Test
    @DisplayName("测试预订金额计算 - 2晚住宿应计算正确金额")
    void testBookingAmountCalculation_TwoNights_ShouldCalculateCorrectly() {
        double roomPrice = 300.0;
        long days = 2;
        LocalDate in = testDataBuilder.tomorrow();
        LocalDate out = testDataBuilder.daysFromNow(2);

        Room room = testDataBuilder.buildRoom(testHotel, "available", roomPrice);
        when(hotelRepository.findById(testHotel.getHotelId())).thenReturn(Optional.of(testHotel));
        when(roomRepository.findByIdForUpdate(room.getRoomId())).thenReturn(Optional.of(room));
        when(roomService.isRoomAvailable(room.getRoomId(), in, out)).thenReturn(true);

        BookingCreateRequest request = testDataBuilder.buildBookingCreateRequest(
                testHotel.getHotelId(),
                room.getRoomId(),
                in,
                out
        );

        Booking result = bookingService.createBooking(request);

        assertNotNull(result);
        assertEquals(roomPrice * days, result.getBookingAmount(), 0.01);
    }

    @Test
    @DisplayName("测试入住日期等于退房日期 - 应抛出异常")
    void testBooking_CheckInEqualsCheckOut_ShouldThrowException() {
        LocalDate sameDate = testDataBuilder.tomorrow();
        BookingCreateRequest request = testDataBuilder.buildBookingCreateRequest(
                testHotel.getHotelId(),
                testRoom.getRoomId(),
                sameDate,
                sameDate
        );

        RuntimeException exception = assertThrows(RuntimeException.class, 
                () -> bookingService.createBooking(request));
        
        assertTrue(exception.getMessage().contains("入住日期必须早于退房日期"));
    }

    @Test
    @DisplayName("测试并发预订模拟 - 多线程竞争同一房间")
    void testConcurrentBookingSimulation_MultithreadedCompetition() throws InterruptedException {
        int threadCount = 5;
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        Room room = testDataBuilder.buildAvailableRoom(testHotel);
        when(hotelRepository.findById(testHotel.getHotelId())).thenReturn(Optional.of(testHotel));
        when(roomRepository.findByIdForUpdate(room.getRoomId())).thenReturn(Optional.of(room));
        when(roomService.isRoomAvailable(room.getRoomId(), checkInDate, checkOutDate))
                .thenReturn(true)
                .thenAnswer(invocation -> {
                    if (successCount.get() > 0) {
                        return false;
                    }
                    return true;
                });

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    BookingCreateRequest req = testDataBuilder.buildBookingCreateRequest(
                            testHotel.getHotelId(),
                            room.getRoomId(),
                            checkInDate,
                            checkOutDate
                    );
                    bookingService.createBooking(req);
                    successCount.incrementAndGet();
                } catch (RuntimeException e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertTrue(successCount.get() >= 1, "至少应有一个成功预订");
        assertEquals(threadCount, successCount.get() + failCount.get());
    }

    @Test
    @DisplayName("测试取消已完成的预订 - 应失败")
    void testCancelBooking_AlreadyCompleted_ShouldThrowException() {
        Booking completedBooking = testDataBuilder.buildPendingBooking(testHotel, testRoom, checkInDate, checkOutDate);
        completedBooking.setBookingStatus("completed");
        when(bookingRepository.findById(completedBooking.getBookingId())).thenReturn(Optional.of(completedBooking));

        RuntimeException exception = assertThrows(RuntimeException.class, 
                () -> bookingService.cancelBooking(completedBooking.getBookingId()));
        
        assertTrue(exception.getMessage().contains("已取消或已完成"));
    }

    @Test
    @DisplayName("测试房间不属于酒店的情况 - 应失败")
    void testCreateBooking_RoomBelongsToDifferentHotel_ShouldThrowException() {
        Hotel otherHotel = testDataBuilder.buildActiveHotel();
        Room otherHotelRoom = testDataBuilder.buildAvailableRoom(otherHotel);
        
        when(hotelRepository.findById(testHotel.getHotelId())).thenReturn(Optional.of(testHotel));
        when(roomRepository.findByIdForUpdate(otherHotelRoom.getRoomId())).thenReturn(Optional.of(otherHotelRoom));

        BookingCreateRequest request = testDataBuilder.buildBookingCreateRequest(
                testHotel.getHotelId(),
                otherHotelRoom.getRoomId(),
                checkInDate,
                checkOutDate
        );

        RuntimeException exception = assertThrows(RuntimeException.class, 
                () -> bookingService.createBooking(request));
        
        assertTrue(exception.getMessage().contains("房间不属于该酒店"));
    }
}
