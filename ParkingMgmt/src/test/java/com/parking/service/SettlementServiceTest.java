package com.parking.service;

import com.parking.builder.TestDataBuilder;
import com.parking.dto.PaymentRequest;
import com.parking.dto.PaymentResponse;
import com.parking.entity.EntryRecord;
import com.parking.entity.ExitRecord;
import com.parking.entity.ParkingLot;
import com.parking.entity.SettlementRecord;
import com.parking.exception.ParkingException;
import com.parking.repository.SettlementRecordRepository;
import com.parking.service.SettlementService.PreCalculationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("结算模块单元测试 - 费用预计算机制")
class SettlementServiceTest {

    @Mock
    private SettlementRecordRepository settlementRecordRepository;

    @Mock
    private ParkingLotService parkingLotService;

    @Mock
    private EntryService entryService;

    @Mock
    private StatisticsService statisticsService;

    @InjectMocks
    private SettlementService settlementService;

    @Test
    @DisplayName("测试费用计算 - 按时收费1小时")
    void testCalculateParkingFee_HourlyRate_1Hour() {
        ParkingLot parkingLot = TestDataBuilder.parkingLotBuilder()
                .hourlyRate(10.0)
                .chargingType("hourly")
                .build();

        LocalDateTime entryTime = LocalDateTime.now().minusHours(1);
        LocalDateTime exitTime = LocalDateTime.now();

        double fee = settlementService.calculateParkingFee(parkingLot, entryTime, exitTime);

        assertEquals(10.0, fee, 0.01);
    }

    @Test
    @DisplayName("测试费用计算 - 按时收费2小时30分钟（向上取整）")
    void testCalculateParkingFee_HourlyRate_2Hours30Minutes() {
        ParkingLot parkingLot = TestDataBuilder.parkingLotBuilder()
                .hourlyRate(10.0)
                .chargingType("hourly")
                .build();

        LocalDateTime entryTime = LocalDateTime.now().minusMinutes(150);
        LocalDateTime exitTime = LocalDateTime.now();

        double fee = settlementService.calculateParkingFee(parkingLot, entryTime, exitTime);

        assertEquals(30.0, fee, 0.01);
    }

    @Test
    @DisplayName("测试费用计算 - 固定收费")
    void testCalculateParkingFee_FixedRate() {
        ParkingLot parkingLot = TestDataBuilder.parkingLotBuilder()
                .chargingType("fixed")
                .fixedFee(50.0)
                .build();

        LocalDateTime entryTime = LocalDateTime.now().minusHours(5);
        LocalDateTime exitTime = LocalDateTime.now();

        double fee = settlementService.calculateParkingFee(parkingLot, entryTime, exitTime);

        assertEquals(50.0, fee, 0.01);
    }

    @Test
    @DisplayName("测试费用计算 - 短时停车（不足1分钟按1分钟计算）")
    void testCalculateParkingFee_ShortParking_LessThan1Minute() {
        ParkingLot parkingLot = TestDataBuilder.parkingLotBuilder()
                .hourlyRate(10.0)
                .chargingType("hourly")
                .build();

        LocalDateTime entryTime = LocalDateTime.now().minusSeconds(30);
        LocalDateTime exitTime = LocalDateTime.now();

        double fee = settlementService.calculateParkingFee(parkingLot, entryTime, exitTime);

        assertEquals(10.0, fee, 0.01);
    }

    @Test
    @DisplayName("测试费用预计算 - 长时停车早预计算")
    void testShouldPreCalculateNow_LongParking_EarlyCalculation() {
        EntryRecord longParkingEntry = TestDataBuilder.entryRecordBuilder()
                .entryTime(LocalDateTime.now().minusHours(3))
                .build();

        boolean shouldCalculate = settlementService.shouldPreCalculateNow(longParkingEntry);

        assertTrue(shouldCalculate);
    }

    @Test
    @DisplayName("测试费用预计算 - 短时停车晚预计算（1小时内不预计算）")
    void testShouldPreCalculateNow_ShortParking_LateCalculation() {
        EntryRecord shortParkingEntry = TestDataBuilder.entryRecordBuilder()
                .entryTime(LocalDateTime.now().minusMinutes(30))
                .build();

        boolean shouldCalculate = settlementService.shouldPreCalculateNow(shortParkingEntry);

        assertFalse(shouldCalculate);
    }

    @Test
    @DisplayName("测试费用预计算 - 不同停车时长的预计算时间差异")
    void testGetPreCalculationTiming_DifferentDurations() {
        EntryRecord shortEntry = TestDataBuilder.entryRecordBuilder()
                .entryTime(LocalDateTime.now().minusMinutes(30))
                .build();

        EntryRecord mediumEntry = TestDataBuilder.entryRecordBuilder()
                .entryTime(LocalDateTime.now().minusMinutes(90))
                .build();

        int shortTiming = settlementService.getPreCalculationTiming(shortEntry);
        int mediumTiming = settlementService.getPreCalculationTiming(mediumEntry);

        assertEquals(-1, shortTiming);
        assertTrue(mediumTiming > 0);
    }

    @Test
    @DisplayName("测试费用预计算 - 成功预计算费用")
    void testPreCalculateFee_Success() {
        String entryId = "entry_001";
        ParkingLot parkingLot = TestDataBuilder.parkingLotBuilder()
                .parkingId("parking_001")
                .hourlyRate(10.0)
                .chargingType("hourly")
                .build();

        EntryRecord entryRecord = TestDataBuilder.entryRecordBuilder()
                .entryId(entryId)
                .parkingId("parking_001")
                .entryTime(LocalDateTime.now().minusHours(2))
                .build();

        when(entryService.getEntryById(entryId)).thenReturn(entryRecord);
        when(parkingLotService.getParkingLotById("parking_001")).thenReturn(parkingLot);

        PreCalculationResult result = settlementService.preCalculateFee(entryId);

        assertNotNull(result);
        assertEquals(entryId, result.getEntryId());
        assertTrue(result.getEstimatedFee() > 0);
        assertTrue(settlementService.hasPreCalculatedFee(entryId));
    }

    @Test
    @DisplayName("测试费用预计算 - 获取已预计算的费用")
    void testGetPreCalculatedFee_Success() {
        String entryId = "entry_001";
        ParkingLot parkingLot = TestDataBuilder.parkingLotBuilder()
                .parkingId("parking_001")
                .hourlyRate(10.0)
                .build();

        EntryRecord entryRecord = TestDataBuilder.entryRecordBuilder()
                .entryId(entryId)
                .parkingId("parking_001")
                .entryTime(LocalDateTime.now().minusHours(2))
                .build();

        when(entryService.getEntryById(entryId)).thenReturn(entryRecord);
        when(parkingLotService.getParkingLotById("parking_001")).thenReturn(parkingLot);

        settlementService.preCalculateFee(entryId);
        PreCalculationResult result = settlementService.getPreCalculatedFee(entryId);

        assertNotNull(result);
        assertEquals(entryId, result.getEntryId());
    }

    @Test
    @DisplayName("测试费用预计算 - 清除预计算结果")
    void testClearPreCalculatedFee_Success() {
        String entryId = "entry_001";
        ParkingLot parkingLot = TestDataBuilder.parkingLotBuilder()
                .parkingId("parking_001")
                .hourlyRate(10.0)
                .build();

        EntryRecord entryRecord = TestDataBuilder.entryRecordBuilder()
                .entryId(entryId)
                .parkingId("parking_001")
                .entryTime(LocalDateTime.now().minusHours(2))
                .build();

        when(entryService.getEntryById(entryId)).thenReturn(entryRecord);
        when(parkingLotService.getParkingLotById("parking_001")).thenReturn(parkingLot);

        settlementService.preCalculateFee(entryId);
        assertTrue(settlementService.hasPreCalculatedFee(entryId));

        settlementService.clearPreCalculatedFee(entryId);
        assertNull(settlementService.getPreCalculatedFee(entryId));
        assertFalse(settlementService.hasPreCalculatedFee(entryId));
    }

    @Test
    @DisplayName("测试创建结算记录 - 成功创建")
    void testCreateSettlement_Success() {
        ParkingLot parkingLot = TestDataBuilder.parkingLotBuilder()
                .parkingId("parking_001")
                .hourlyRate(10.0)
                .build();

        EntryRecord entryRecord = TestDataBuilder.entryRecordBuilder()
                .entryId("entry_001")
                .parkingId("parking_001")
                .entryTime(LocalDateTime.now().minusHours(2))
                .build();

        ExitRecord exitRecord = TestDataBuilder.exitRecordBuilder()
                .exitId("exit_001")
                .entryId("entry_001")
                .exitTime(LocalDateTime.now())
                .build();

        when(parkingLotService.getParkingLotById("parking_001")).thenReturn(parkingLot);
        when(settlementRecordRepository.save(any(SettlementRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SettlementRecord result = settlementService.createSettlement(entryRecord, exitRecord);

        assertNotNull(result);
        assertNotNull(result.getSettlementId());
        assertEquals("entry_001", result.getEntryId());
        assertEquals("exit_001", result.getExitId());
        assertEquals("pending", result.getPaymentStatus());
        assertTrue(result.getParkingFee() >= 20.0);
    }

    @Test
    @DisplayName("测试支付处理 - 成功支付")
    void testProcessPayment_Success() {
        SettlementRecord settlement = TestDataBuilder.settlementRecordBuilder()
                .settlementId("settlement_001")
                .parkingFee(20.0)
                .paymentStatus("pending")
                .build();

        PaymentRequest request = TestDataBuilder.paymentRequestBuilder()
                .settlementId("settlement_001")
                .paymentMethod("wechat")
                .build();

        when(settlementRecordRepository.findBySettlementId("settlement_001")).thenReturn(Optional.of(settlement));
        when(settlementRecordRepository.save(any(SettlementRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(statisticsService).addTotalAmount(20.0);

        PaymentResponse response = settlementService.processPayment(request);

        assertNotNull(response);
        assertEquals("paid", response.getStatus());
        assertEquals("settlement_001", response.getSettlementId());
        assertEquals(20.0, response.getAmount(), 0.01);
        assertEquals("wechat", response.getPaymentMethod());
    }

    @Test
    @DisplayName("测试支付处理 - 订单已支付时抛出异常")
    void testProcessPayment_AlreadyPaid() {
        SettlementRecord settlement = TestDataBuilder.settlementRecordBuilder()
                .settlementId("settlement_001")
                .paymentStatus("paid")
                .build();

        PaymentRequest request = TestDataBuilder.paymentRequestBuilder()
                .settlementId("settlement_001")
                .build();

        when(settlementRecordRepository.findBySettlementId("settlement_001")).thenReturn(Optional.of(settlement));

        ParkingException exception = assertThrows(ParkingException.class, () -> {
            settlementService.processPayment(request);
        });

        assertEquals(400, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("已支付"));
    }

    @Test
    @DisplayName("测试支付重试机制 - 成功重试")
    void testRetryPayment_Success() {
        String settlementId = "settlement_001";
        SettlementRecord settlement = TestDataBuilder.settlementRecordBuilder()
                .settlementId(settlementId)
                .parkingFee(20.0)
                .paymentStatus("pending")
                .paymentMethod("wechat")
                .build();

        when(settlementRecordRepository.findBySettlementId(settlementId)).thenReturn(Optional.of(settlement));
        when(settlementRecordRepository.save(any(SettlementRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(statisticsService).addTotalAmount(20.0);

        SettlementRecord result = settlementService.retryPayment(settlementId);

        assertNotNull(result);
        assertEquals("paid", result.getPaymentStatus());
        assertNotNull(result.getSettlementTime());
    }

    @Test
    @DisplayName("测试支付重试机制 - 超过最大重试次数")
    void testRetryPayment_MaxRetryExceeded() {
        String settlementId = "settlement_001";
        SettlementRecord settlement = TestDataBuilder.settlementRecordBuilder()
                .settlementId(settlementId)
                .paymentStatus("pending")
                .paymentMethod("wechat")
                .build();

        when(settlementRecordRepository.findBySettlementId(settlementId)).thenReturn(Optional.of(settlement));

        for (int i = 0; i < settlementService.getMaxRetryAttempts(); i++) {
            settlementService.retryPayment(settlementId);
            reset(settlementRecordRepository);
            when(settlementRecordRepository.findBySettlementId(settlementId)).thenReturn(Optional.of(settlement));
        }

        assertThrows(ParkingException.class, () -> {
            settlementService.retryPayment(settlementId);
        });
    }

    @Test
    @DisplayName("测试支付重试机制 - 检查是否可以重试")
    void testCanRetry_ShouldReturnTrue() {
        String settlementId = "settlement_001";

        assertTrue(settlementService.canRetry(settlementId));
        assertEquals(0, settlementService.getRetryCount(settlementId));
    }

    @Test
    @DisplayName("测试支付重试机制 - 重置重试计数器")
    void testResetRetryCounter_Success() {
        String settlementId = "settlement_001";
        SettlementRecord settlement = TestDataBuilder.settlementRecordBuilder()
                .settlementId(settlementId)
                .paymentStatus("pending")
                .paymentMethod("wechat")
                .build();

        when(settlementRecordRepository.findBySettlementId(settlementId)).thenReturn(Optional.of(settlement));

        settlementService.retryPayment(settlementId);
        assertTrue(settlementService.getRetryCount(settlementId) > 0);

        settlementService.resetRetryCounter(settlementId);
        assertEquals(0, settlementService.getRetryCount(settlementId));
    }

    @Test
    @DisplayName("测试获取结算记录 - 成功获取")
    void testGetSettlementById_Success() {
        SettlementRecord settlement = TestDataBuilder.settlementRecordBuilder()
                .settlementId("settlement_001")
                .parkingFee(20.0)
                .build();

        when(settlementRecordRepository.findBySettlementId("settlement_001")).thenReturn(Optional.of(settlement));

        SettlementRecord result = settlementService.getSettlementById("settlement_001");

        assertNotNull(result);
        assertEquals("settlement_001", result.getSettlementId());
        assertEquals(20.0, result.getParkingFee(), 0.01);
    }

    @Test
    @DisplayName("测试获取结算记录 - 记录不存在时抛出异常")
    void testGetSettlementById_NotFound() {
        when(settlementRecordRepository.findBySettlementId("nonexistent")).thenReturn(Optional.empty());

        ParkingException exception = assertThrows(ParkingException.class, () -> {
            settlementService.getSettlementById("nonexistent");
        });

        assertEquals(404, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("结算记录不存在"));
    }

    @Test
    @DisplayName("测试不同收费类型的费用计算 - 按时收费 vs 固定收费")
    void testCalculateParkingFee_DifferentChargingTypes() {
        ParkingLot hourlyParking = TestDataBuilder.parkingLotBuilder()
                .chargingType("hourly")
                .hourlyRate(10.0)
                .build();

        ParkingLot fixedParking = TestDataBuilder.parkingLotBuilder()
                .chargingType("fixed")
                .fixedFee(50.0)
                .build();

        LocalDateTime entryTime = LocalDateTime.now().minusHours(3);
        LocalDateTime exitTime = LocalDateTime.now();

        double hourlyFee = settlementService.calculateParkingFee(hourlyParking, entryTime, exitTime);
        double fixedFee = settlementService.calculateParkingFee(fixedParking, entryTime, exitTime);

        assertEquals(30.0, hourlyFee, 0.01);
        assertEquals(50.0, fixedFee, 0.01);
    }
}
