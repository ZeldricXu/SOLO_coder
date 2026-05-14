package com.hotelbooking.service;

import com.hotelbooking.builder.TestDataBuilder;
import com.hotelbooking.dto.CheckInRequest;
import com.hotelbooking.model.Booking;
import com.hotelbooking.model.CheckIn;
import com.hotelbooking.model.Hotel;
import com.hotelbooking.model.Room;
import com.hotelbooking.repository.BookingRepository;
import com.hotelbooking.repository.CheckInRepository;
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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CheckInServiceTest {

    @Mock
    private CheckInRepository checkInRepository;

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private AnalysisService analysisService;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private CheckInService checkInService;

    private TestDataBuilder testDataBuilder;
    private Hotel testHotel;
    private Room testRoom;
    private Booking testBooking;
    private LocalDate checkInDate;
    private LocalDate checkOutDate;

    @BeforeEach
    void setUp() {
        testDataBuilder = new TestDataBuilder();
        testHotel = testDataBuilder.buildActiveHotel();
        testRoom = testDataBuilder.buildAvailableRoom(testHotel);
        checkInDate = testDataBuilder.tomorrow();
        checkOutDate = testDataBuilder.daysFromNow(3);
        testBooking = testDataBuilder.buildConfirmedBooking(testHotel, testRoom, checkInDate, checkOutDate);

        when(bookingRepository.findById(testBooking.getBookingId())).thenReturn(Optional.of(testBooking));
        when(roomRepository.findByIdForUpdate(testRoom.getRoomId())).thenReturn(Optional.of(testRoom));
        when(checkInRepository.save(any(CheckIn.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("测试入住登记 - 成功登记")
    void testCheckIn_Successful_ShouldCreateCheckInRecord() {
        CheckInRequest request = testDataBuilder.buildCheckInRequest(testBooking.getBookingId());

        CheckIn result = checkInService.checkIn(request);

        assertNotNull(result);
        assertEquals(testBooking.getBookingId(), result.getBookingId());
        assertEquals("checked_in", result.getCheckinStatus());
        assertNotNull(result.getCheckinId());
        assertNotNull(result.getCheckinTime());

        verify(checkInRepository, times(1)).save(any(CheckIn.class));
        verify(bookingRepository).save(argThat(b -> "checked_in".equals(b.getBookingStatus())));
        verify(roomRepository).save(argThat(r -> "occupied".equals(r.getRoomStatus())));
    }

    @Test
    @DisplayName("测试入住登记异步化 - 提交后立即返回Future")
    void testAsyncCheckIn_ShouldReturnFutureImmediately() {
        AsyncCheckInService asyncService = new AsyncCheckInService(checkInService);
        CheckInRequest request = testDataBuilder.buildCheckInRequest(testBooking.getBookingId());

        long startTime = System.currentTimeMillis();
        CompletableFuture<CheckIn> future = asyncService.asyncCheckIn(request);

        assertTrue(System.currentTimeMillis() - startTime < 100);
        assertNotNull(future);
    }

    @Test
    @DisplayName("测试入住登记异步化 - 后台Worker执行入住登记处理")
    void testAsyncCheckIn_BackgroundWorker_ShouldProcessCheckIn() throws Exception {
        AsyncCheckInService asyncService = new AsyncCheckInService(checkInService);
        CheckInRequest request = testDataBuilder.buildCheckInRequest(testBooking.getBookingId());

        CompletableFuture<CheckIn> future = asyncService.asyncCheckIn(request);
        CheckIn result = future.get(5, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals(testBooking.getBookingId(), result.getBookingId());
        verify(checkInRepository, atLeastOnce()).save(any(CheckIn.class));
    }

    @Test
    @DisplayName("测试预订确认完成后立即返回响应不阻塞 - 异步处理")
    void testAsyncCheckIn_ImmediateReturn_ShouldNotBlock() throws InterruptedException {
        AtomicInteger callbackInvoked = new AtomicInteger(0);
        AtomicReference<CheckIn> resultRef = new AtomicReference<>(null);
        CountDownLatch latch = new CountDownLatch(1);

        AsyncCheckInService asyncService = new AsyncCheckInService(checkInService);
        CheckInRequest request = testDataBuilder.buildCheckInRequest(testBooking.getBookingId());

        long startTime = System.currentTimeMillis();
        asyncService.processCheckInAsync(request, new AsyncCheckInService.CheckInCallback() {
            @Override
            public void onSuccess(CheckIn checkIn) {
                callbackInvoked.incrementAndGet();
                resultRef.set(checkIn);
                latch.countDown();
            }

            @Override
            public void onFailure(Exception e) {
                callbackInvoked.incrementAndGet();
                latch.countDown();
            }
        });

        assertTrue(System.currentTimeMillis() - startTime < 50);

        latch.await(5, TimeUnit.SECONDS);
        assertEquals(1, callbackInvoked.get());
        assertNotNull(resultRef.get());
    }

    @Test
    @DisplayName("测试入住身份验证正确性 - 有效证件应通过")
    void testIdentityVerification_ValidId_ShouldPass() {
        CheckInRequest request = testDataBuilder.buildCheckInRequest(testBooking.getBookingId());
        request.setCustomerIdNumber("110101199001011234");

        CheckIn result = checkInService.checkIn(request);

        assertNotNull(result);
        assertEquals("id_card", result.getCustomerIdType());
        assertEquals("110101199001011234", result.getCustomerIdNumber());
    }

    @Test
    @DisplayName("测试入住身份验证正确性 - 无效证件应失败")
    void testIdentityVerification_InvalidId_ShouldFail() {
        CheckInRequest request = testDataBuilder.buildCheckInRequest(testBooking.getBookingId());
        request.setCustomerIdNumber("123");

        RuntimeException exception = assertThrows(RuntimeException.class, 
                () -> checkInService.checkIn(request));
        
        assertTrue(exception.getMessage().contains("身份验证失败") || 
                   exception.getMessage().contains("证件号码"));
    }

    @Test
    @DisplayName("测试入住身份验证 - 空证件号码应失败")
    void testIdentityVerification_EmptyId_ShouldFail() {
        CheckInRequest request = testDataBuilder.buildCheckInRequest(testBooking.getBookingId());
        request.setCustomerIdNumber("");

        RuntimeException exception = assertThrows(RuntimeException.class, 
                () -> checkInService.checkIn(request));
        
        assertTrue(exception.getMessage().contains("不能为空"));
    }

    @Test
    @DisplayName("测试登记失败时的重试机制 - 成功后停止重试")
    void testRetryMechanism_SuccessAfterFirstFail_ShouldSucceed() throws Exception {
        AtomicInteger attemptCount = new AtomicInteger(0);
        when(checkInRepository.save(any(CheckIn.class)))
                .thenAnswer(invocation -> {
                    int attempt = attemptCount.incrementAndGet();
                    if (attempt < 2) {
                        throw new RuntimeException("临时错误");
                    }
                    return invocation.getArgument(0);
                });

        AsyncCheckInService asyncService = new AsyncCheckInService(checkInService);
        CheckInRequest request = testDataBuilder.buildCheckInRequest(testBooking.getBookingId());

        CompletableFuture<CheckIn> future = asyncService.asyncCheckIn(request);
        CheckIn result = future.get(10, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals(2, attemptCount.get());
    }

    @Test
    @DisplayName("测试登记失败时的重试机制 - 达到最大重试次数后失败")
    void testRetryMechanism_MaxAttemptsReached_ShouldFail() throws Exception {
        AtomicInteger attemptCount = new AtomicInteger(0);
        when(checkInRepository.save(any(CheckIn.class)))
                .thenAnswer(invocation -> {
                    attemptCount.incrementAndGet();
                    throw new RuntimeException("持续错误");
                });

        AsyncCheckInService asyncService = new AsyncCheckInService(checkInService);
        CheckInRequest request = testDataBuilder.buildCheckInRequest(testBooking.getBookingId());

        CompletableFuture<CheckIn> future = asyncService.asyncCheckIn(request);

        assertThrows(Exception.class, () -> future.get(15, TimeUnit.SECONDS));
        assertEquals(3, attemptCount.get());
    }

    @Test
    @DisplayName("测试最大重试次数配置")
    void testMaxRetryAttempts_ShouldBeThree() {
        assertEquals(3, AsyncCheckInService.getMaxRetryAttempts());
    }

    @Test
    @DisplayName("测试入住登记 - 已取消的预订应失败")
    void testCheckIn_CancelledBooking_ShouldFail() {
        testBooking.setBookingStatus("cancelled");
        when(bookingRepository.findById(testBooking.getBookingId())).thenReturn(Optional.of(testBooking));

        CheckInRequest request = testDataBuilder.buildCheckInRequest(testBooking.getBookingId());

        RuntimeException exception = assertThrows(RuntimeException.class, 
                () -> checkInService.checkIn(request));
        
        assertTrue(exception.getMessage().contains("已取消"));
    }

    @Test
    @DisplayName("测试入住登记 - 已入住的预订应失败")
    void testCheckIn_AlreadyCheckedIn_ShouldFail() {
        testBooking.setBookingStatus("checked_in");
        when(bookingRepository.findById(testBooking.getBookingId())).thenReturn(Optional.of(testBooking));

        CheckInRequest request = testDataBuilder.buildCheckInRequest(testBooking.getBookingId());

        RuntimeException exception = assertThrows(RuntimeException.class, 
                () -> checkInService.checkIn(request));
        
        assertTrue(exception.getMessage().contains("重复操作"));
    }

    @Test
    @DisplayName("测试入住登记 - 已完成的预订应失败")
    void testCheckIn_CompletedBooking_ShouldFail() {
        testBooking.setBookingStatus("completed");
        when(bookingRepository.findById(testBooking.getBookingId())).thenReturn(Optional.of(testBooking));

        CheckInRequest request = testDataBuilder.buildCheckInRequest(testBooking.getBookingId());

        RuntimeException exception = assertThrows(RuntimeException.class, 
                () -> checkInService.checkIn(request));
        
        assertTrue(exception.getMessage().contains("已完成"));
    }

    @Test
    @DisplayName("测试入住登记 - 预订不存在应失败")
    void testCheckIn_BookingNotFound_ShouldFail() {
        String nonExistentBookingId = "booking_nonexistent";
        when(bookingRepository.findById(nonExistentBookingId)).thenReturn(Optional.empty());

        CheckInRequest request = testDataBuilder.buildCheckInRequest(nonExistentBookingId);

        RuntimeException exception = assertThrows(RuntimeException.class, 
                () -> checkInService.checkIn(request));
        
        assertTrue(exception.getMessage().contains("预订不存在"));
    }

    @Test
    @DisplayName("测试入住登记 - 待确认状态应允许入住")
    void testCheckIn_PendingStatus_ShouldAllow() {
        testBooking.setBookingStatus("pending");
        when(bookingRepository.findById(testBooking.getBookingId())).thenReturn(Optional.of(testBooking));

        CheckInRequest request = testDataBuilder.buildCheckInRequest(testBooking.getBookingId());

        CheckIn result = checkInService.checkIn(request);

        assertNotNull(result);
        assertEquals("checked_in", result.getCheckinStatus());
    }

    @Test
    @DisplayName("测试入住登记 - 护照类型证件")
    void testCheckIn_PassportIdType_ShouldRecordCorrectly() {
        CheckInRequest request = testDataBuilder.buildCheckInRequest(testBooking.getBookingId());
        request.setCustomerIdType("passport");
        request.setCustomerIdNumber("E12345678");

        CheckIn result = checkInService.checkIn(request);

        assertNotNull(result);
        assertEquals("passport", result.getCustomerIdType());
    }

    @Test
    @DisplayName("测试入住登记 - 成功后应增加入住统计")
    void testCheckIn_Successful_ShouldIncrementCheckInCount() {
        CheckInRequest request = testDataBuilder.buildCheckInRequest(testBooking.getBookingId());

        checkInService.checkIn(request);

        verify(analysisService, times(1)).incrementCheckInCount(testHotel.getHotelId());
    }

    @Test
    @DisplayName("测试入住登记 - 成功后应记录历史")
    void testCheckIn_Successful_ShouldRecordHistory() {
        CheckInRequest request = testDataBuilder.buildCheckInRequest(testBooking.getBookingId());

        checkInService.checkIn(request);

        verify(historyService, times(1))
                .recordCheckInHistory(any(CheckIn.class), any(Booking.class), anyString(), anyString());
    }

    @Test
    @DisplayName("测试退房登记 - 成功退房")
    void testCheckOut_Successful_ShouldUpdateStatus() {
        CheckIn checkIn = testDataBuilder.buildCheckIn(testBooking, "checked_in");
        when(checkInRepository.findById(checkIn.getCheckinId())).thenReturn(Optional.of(checkIn));
        when(checkInRepository.save(any(CheckIn.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CheckIn result = checkInService.checkOut(checkIn.getCheckinId());

        assertEquals("checked_out", result.getCheckinStatus());
    }

    @Test
    @DisplayName("测试退房登记 - 未入住状态应失败")
    void testCheckOut_NotCheckedIn_ShouldFail() {
        CheckIn checkIn = testDataBuilder.buildCheckIn(testBooking, "pending");
        when(checkInRepository.findById(checkIn.getCheckinId())).thenReturn(Optional.of(checkIn));

        RuntimeException exception = assertThrows(RuntimeException.class, 
                () -> checkInService.checkOut(checkIn.getCheckinId()));
        
        assertTrue(exception.getMessage().contains("不允许退房"));
    }
}
