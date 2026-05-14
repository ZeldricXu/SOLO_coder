package com.hotelbooking.service;

import com.hotelbooking.builder.TestDataBuilder;
import com.hotelbooking.model.*;
import com.hotelbooking.repository.*;
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
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SettlementServiceTest {

    @Mock
    private SettlementRepository settlementRepository;

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private CheckInRepository checkInRepository;

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private ServiceRecordRepository serviceRecordRepository;

    @Mock
    private AnalysisService analysisService;

    @Mock
    private HistoryService historyService;

    @Mock
    private ReviewService reviewService;

    @InjectMocks
    private SettlementService settlementService;

    private TestDataBuilder testDataBuilder;
    private Hotel testHotel;
    private Room testRoom;
    private Booking testBooking;
    private CheckIn testCheckIn;
    private LocalDate checkInDate;
    private LocalDate checkOutDate;

    @BeforeEach
    void setUp() {
        testDataBuilder = new TestDataBuilder();
        testHotel = testDataBuilder.buildActiveHotel();
        testRoom = testDataBuilder.buildAvailableRoom(testHotel);
        checkInDate = testDataBuilder.tomorrow();
        checkOutDate = testDataBuilder.daysFromNow(3);
        testBooking = testDataBuilder.buildCheckedInBooking(testHotel, testRoom, checkInDate, checkOutDate);
        testCheckIn = testDataBuilder.buildCheckIn(testBooking, "checked_in");
        
        testCheckIn.setCheckinTime(LocalDateTime.now().minusDays(2));
        testRoom.setRoomPrice(300.0);

        when(bookingRepository.findById(testBooking.getBookingId())).thenReturn(Optional.of(testBooking));
        when(checkInRepository.findByBookingId(testBooking.getBookingId())).thenReturn(Optional.of(testCheckIn));
        when(roomRepository.findById(testRoom.getRoomId())).thenReturn(Optional.of(testRoom));
        when(roomRepository.findByIdForUpdate(testRoom.getRoomId())).thenReturn(Optional.of(testRoom));
        when(settlementRepository.findByBookingId(testBooking.getBookingId())).thenReturn(Optional.empty());
        when(settlementRepository.save(any(Settlement.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(checkInRepository.save(any(CheckIn.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(serviceRecordRepository.findByRoomId(testRoom.getRoomId())).thenReturn(Collections.emptyList());
    }

    @Test
    @DisplayName("测试费用计算结果校验 - 正确计算应通过校验")
    void testFeeCalculationValidation_CorrectCalculation_ShouldPass() {
        Room room = testDataBuilder.buildAvailableRoom(testHotel);
        room.setRoomPrice(300.0);
        long days = 2;
        double calculatedRoomCharge = 600.0;

        FeeValidationService.ValidationResult result = 
            new FeeValidationService(serviceRecordRepository)
                .validateRoomCharge(room, days, calculatedRoomCharge);

        assertTrue(result.isValid());
        assertEquals("校验通过", result.getMessage());
    }

    @Test
    @DisplayName("测试费用计算结果校验 - 错误计算应失败校验")
    void testFeeCalculationValidation_IncorrectCalculation_ShouldFail() {
        Room room = testDataBuilder.buildAvailableRoom(testHotel);
        room.setRoomPrice(300.0);
        long days = 2;
        double incorrectRoomCharge = 500.0;

        FeeValidationService.ValidationResult result = 
            new FeeValidationService(serviceRecordRepository)
                .validateRoomCharge(room, days, incorrectRoomCharge);

        assertFalse(result.isValid());
        assertTrue(result.getMessage().contains("计算错误"));
    }

    @Test
    @DisplayName("测试住宿费用天数关联校验 - 天数为0应失败")
    void testRoomChargeValidation_ZeroDays_ShouldFail() {
        Room room = testDataBuilder.buildAvailableRoom(testHotel);
        room.setRoomPrice(300.0);
        long zeroDays = 0;

        FeeValidationService.ValidationResult result = 
            new FeeValidationService(serviceRecordRepository)
                .validateRoomCharge(room, zeroDays, 0.0);

        assertFalse(result.isValid());
        assertTrue(result.getMessage().contains("必须大于0"));
    }

    @Test
    @DisplayName("测试住宿费用天数关联校验 - 负数天数应失败")
    void testRoomChargeValidation_NegativeDays_ShouldFail() {
        Room room = testDataBuilder.buildAvailableRoom(testHotel);
        room.setRoomPrice(300.0);
        long negativeDays = -1;

        FeeValidationService.ValidationResult result = 
            new FeeValidationService(serviceRecordRepository)
                .validateRoomCharge(room, negativeDays, -300.0);

        assertFalse(result.isValid());
    }

    @Test
    @DisplayName("测试服务费用项目关联校验 - 有服务记录时应正确累计")
    void testServiceChargeValidation_WithServices_ShouldCalculateCorrectly() {
        ServiceRecord cleaning = testDataBuilder.buildCleaningService(testRoom, "completed");
        ServiceRecord laundry = testDataBuilder.buildLaundryService(testRoom, "completed");
        List<ServiceRecord> services = List.of(cleaning, laundry);
        
        when(serviceRecordRepository.findByRoomId(testRoom.getRoomId())).thenReturn(services);

        FeeValidationService validationService = new FeeValidationService(serviceRecordRepository);
        double correctCharge = 50.0 + 30.0;
        FeeValidationService.ValidationResult result = 
            validationService.validateServiceCharge(testRoom.getRoomId(), correctCharge);

        assertTrue(result.isValid());
    }

    @Test
    @DisplayName("测试服务费用项目关联校验 - 服务费用错误时应失败")
    void testServiceChargeValidation_IncorrectCharge_ShouldFail() {
        ServiceRecord cleaning = testDataBuilder.buildCleaningService(testRoom, "completed");
        List<ServiceRecord> services = List.of(cleaning);
        
        when(serviceRecordRepository.findByRoomId(testRoom.getRoomId())).thenReturn(services);

        FeeValidationService validationService = new FeeValidationService(serviceRecordRepository);
        double incorrectCharge = 100.0;
        FeeValidationService.ValidationResult result = 
            validationService.validateServiceCharge(testRoom.getRoomId(), incorrectCharge);

        assertFalse(result.isValid());
    }

    @Test
    @DisplayName("测试服务费用项目关联校验 - 未完成服务不计入")
    void testServiceChargeValidation_IncompleteServices_ShouldNotCount() {
        ServiceRecord completedService = testDataBuilder.buildCleaningService(testRoom, "completed");
        ServiceRecord pendingService = testDataBuilder.buildLaundryService(testRoom, "pending");
        List<ServiceRecord> services = List.of(completedService, pendingService);
        
        when(serviceRecordRepository.findByRoomId(testRoom.getRoomId())).thenReturn(services);

        FeeValidationService validationService = new FeeValidationService(serviceRecordRepository);
        double correctCharge = 50.0;
        FeeValidationService.ValidationResult result = 
            validationService.validateServiceCharge(testRoom.getRoomId(), correctCharge);

        assertTrue(result.isValid());
    }

    @Test
    @DisplayName("测试总费用校验 - 正确总和应通过")
    void testTotalAmountValidation_CorrectSum_ShouldPass() {
        double roomCharge = 600.0;
        double serviceCharge = 80.0;
        double total = 680.0;

        FeeValidationService.ValidationResult result = 
            new FeeValidationService(serviceRecordRepository)
                .validateTotalAmount(roomCharge, serviceCharge, total);

        assertTrue(result.isValid());
    }

    @Test
    @DisplayName("测试总费用校验 - 错误总和应失败")
    void testTotalAmountValidation_IncorrectSum_ShouldFail() {
        double roomCharge = 600.0;
        double serviceCharge = 80.0;
        double incorrectTotal = 650.0;

        FeeValidationService.ValidationResult result = 
            new FeeValidationService(serviceRecordRepository)
                .validateTotalAmount(roomCharge, serviceCharge, incorrectTotal);

        assertFalse(result.isValid());
    }

    @Test
    @DisplayName("测试总费用校验 - 负数费用应失败")
    void testTotalAmountValidation_NegativeAmount_ShouldFail() {
        double roomCharge = -100.0;
        double serviceCharge = 80.0;
        double total = -20.0;

        FeeValidationService.ValidationResult result = 
            new FeeValidationService(serviceRecordRepository)
                .validateTotalAmount(roomCharge, serviceCharge, total);

        assertFalse(result.isValid());
    }

    @Test
    @DisplayName("测试计算异常时的日志记录 - 负数总金额应记录错误日志")
    void testCalculateFeeException_ShouldLogError() {
        when(roomRepository.findById(testRoom.getRoomId())).thenReturn(Optional.of(testRoom));
        when(serviceRecordRepository.findByRoomId(testRoom.getRoomId())).thenReturn(Collections.emptyList());

        assertDoesNotThrow(() -> {
            Settlement result = settlementService.calculateFee(testBooking.getBookingId());
            assertNotNull(result);
            assertEquals(testBooking.getBookingId(), result.getBookingId());
        });
    }

    @Test
    @DisplayName("测试校验失败时的拒绝结算处理 - 预订不存在时应拒绝")
    void testSettlementValidation_BookingNotFound_ShouldReject() {
        String nonExistentBookingId = "booking_nonexistent";
        when(bookingRepository.findById(nonExistentBookingId)).thenReturn(Optional.empty());

        RuntimeException exception = assertThrows(RuntimeException.class, 
                () -> settlementService.checkOutAndSettle(nonExistentBookingId, "cash"));
        
        assertTrue(exception.getMessage().contains("预订不存在"));
        verify(settlementRepository, never()).save(any(Settlement.class));
    }

    @Test
    @DisplayName("测试校验失败时的拒绝结算处理 - 入住记录不存在时应拒绝")
    void testSettlementValidation_CheckInNotFound_ShouldReject() {
        when(checkInRepository.findByBookingId(testBooking.getBookingId())).thenReturn(Optional.empty());

        RuntimeException exception = assertThrows(RuntimeException.class, 
                () -> settlementService.checkOutAndSettle(testBooking.getBookingId(), "cash"));
        
        assertTrue(exception.getMessage().contains("入住记录不存在"));
    }

    @Test
    @DisplayName("测试校验失败时的拒绝结算处理 - 未入住状态应拒绝")
    void testSettlementValidation_NotCheckedIn_ShouldReject() {
        testCheckIn.setCheckinStatus("pending");
        when(checkInRepository.findByBookingId(testBooking.getBookingId())).thenReturn(Optional.of(testCheckIn));

        RuntimeException exception = assertThrows(RuntimeException.class, 
                () -> settlementService.checkOutAndSettle(testBooking.getBookingId(), "cash"));
        
        assertTrue(exception.getMessage().contains("不允许退房结算"));
    }

    @Test
    @DisplayName("测试校验失败时的拒绝结算处理 - 已结算的预订应拒绝")
    void testSettlementValidation_AlreadySettled_ShouldReject() {
        Settlement existingSettlement = testDataBuilder.buildSettlement(testBooking, 600.0, 50.0);
        when(settlementRepository.findByBookingId(testBooking.getBookingId()))
                .thenReturn(Optional.of(existingSettlement));

        RuntimeException exception = assertThrows(RuntimeException.class, 
                () -> settlementService.checkOutAndSettle(testBooking.getBookingId(), "cash"));
        
        assertTrue(exception.getMessage().contains("已完成结算"));
    }

    @Test
    @DisplayName("测试退房结算 - 无服务费用时应正确计算")
    void testCheckOutAndSettle_NoServiceFee_ShouldCalculateCorrectly() {
        when(serviceRecordRepository.findByRoomId(testRoom.getRoomId())).thenReturn(Collections.emptyList());
        testCheckIn.setCheckinTime(LocalDateTime.now().minusDays(2));

        Settlement result = settlementService.checkOutAndSettle(testBooking.getBookingId(), "cash");

        assertNotNull(result);
        assertEquals(testBooking.getBookingId(), result.getBookingId());
        assertTrue(result.getRoomCharge() > 0);
        assertEquals(0.0, result.getServiceCharge(), 0.01);
        assertEquals(result.getRoomCharge(), result.getTotalAmount(), 0.01);
        assertEquals("paid", result.getSettlementStatus());
    }

    @Test
    @DisplayName("测试退房结算 - 有服务费用时应正确计算")
    void testCheckOutAndSettle_WithServiceFee_ShouldCalculateCorrectly() {
        ServiceRecord cleaning = testDataBuilder.buildCleaningService(testRoom, "completed");
        ServiceRecord laundry = testDataBuilder.buildLaundryService(testRoom, "completed");
        when(serviceRecordRepository.findByRoomId(testRoom.getRoomId())).thenReturn(List.of(cleaning, laundry));
        testCheckIn.setCheckinTime(LocalDateTime.now().minusDays(2));

        Settlement result = settlementService.checkOutAndSettle(testBooking.getBookingId(), "cash");

        assertNotNull(result);
        assertTrue(result.getRoomCharge() > 0);
        assertEquals(80.0, result.getServiceCharge(), 0.01);
        assertEquals(result.getRoomCharge() + result.getServiceCharge(), result.getTotalAmount(), 0.01);
    }

    @Test
    @DisplayName("测试退房结算 - 成功后应更新房间状态为空闲")
    void testCheckOutAndSettle_Successful_ShouldUpdateRoomToAvailable() {
        when(serviceRecordRepository.findByRoomId(testRoom.getRoomId())).thenReturn(Collections.emptyList());
        testCheckIn.setCheckinTime(LocalDateTime.now().minusDays(2));

        settlementService.checkOutAndSettle(testBooking.getBookingId(), "cash");

        verify(roomRepository).save(argThat(room -> "available".equals(room.getRoomStatus())));
    }

    @Test
    @DisplayName("测试退房结算 - 成功后应更新预订状态为已完成")
    void testCheckOutAndSettle_Successful_ShouldUpdateBookingToCompleted() {
        when(serviceRecordRepository.findByRoomId(testRoom.getRoomId())).thenReturn(Collections.emptyList());
        testCheckIn.setCheckinTime(LocalDateTime.now().minusDays(2));

        settlementService.checkOutAndSettle(testBooking.getBookingId(), "cash");

        verify(bookingRepository).save(argThat(booking -> "completed".equals(booking.getBookingStatus())));
    }

    @Test
    @DisplayName("测试退房结算 - 成功后应请求评价")
    void testCheckOutAndSettle_Successful_ShouldRequestReview() {
        when(serviceRecordRepository.findByRoomId(testRoom.getRoomId())).thenReturn(Collections.emptyList());
        testCheckIn.setCheckinTime(LocalDateTime.now().minusDays(2));

        settlementService.checkOutAndSettle(testBooking.getBookingId(), "cash");

        verify(reviewService, times(1)).requestReview(testBooking);
    }

    @Test
    @DisplayName("测试退房结算 - 应记录收入统计")
    void testCheckOutAndSettle_Successful_ShouldRecordRevenue() {
        when(serviceRecordRepository.findByRoomId(testRoom.getRoomId())).thenReturn(Collections.emptyList());
        testCheckIn.setCheckinTime(LocalDateTime.now().minusDays(2));

        Settlement result = settlementService.checkOutAndSettle(testBooking.getBookingId(), "cash");

        verify(analysisService, times(1)).addRevenue(eq(testHotel.getHotelId()), eq(result.getTotalAmount()));
    }

    @Test
    @DisplayName("测试计算费用 - 应计算住宿和服务费用")
    void testCalculateFee_ShouldCalculateRoomAndServiceCharges() {
        ServiceRecord cleaning = testDataBuilder.buildCleaningService(testRoom, "completed");
        when(serviceRecordRepository.findByRoomId(testRoom.getRoomId())).thenReturn(List.of(cleaning));
        testCheckIn.setCheckinTime(LocalDateTime.now().minusDays(1));

        Settlement result = settlementService.calculateFee(testBooking.getBookingId());

        assertNotNull(result);
        assertTrue(result.getRoomCharge() > 0);
        assertTrue(result.getServiceCharge() > 0);
        assertEquals(result.getRoomCharge() + result.getServiceCharge(), result.getTotalAmount(), 0.01);
    }

    @Test
    @DisplayName("测试费用校验 - 浮点数精度处理")
    void testFeeValidation_FloatingPointPrecision() {
        Room room = testDataBuilder.buildAvailableRoom(testHotel);
        room.setRoomPrice(99.99);
        long days = 3;
        double calculated = 299.97;

        FeeValidationService.ValidationResult result = 
            new FeeValidationService(serviceRecordRepository)
                .validateRoomCharge(room, days, calculated);

        assertTrue(result.isValid());
    }

    @Test
    @DisplayName("测试费用校验 - 小误差应容忍")
    void testFeeValidation_SmallTolerance_ShouldPass() {
        double roomCharge = 600.0;
        double serviceCharge = 50.0;
        double totalWithSmallError = 650.001;

        FeeValidationService.ValidationResult result = 
            new FeeValidationService(serviceRecordRepository)
                .validateTotalAmount(roomCharge, serviceCharge, totalWithSmallError);

        assertTrue(result.isValid());
    }
}
