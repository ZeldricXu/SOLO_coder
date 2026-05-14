package com.parking.service;

import com.parking.builder.TestDataBuilder;
import com.parking.dto.ExitRequest;
import com.parking.dto.ExitResponse;
import com.parking.entity.EntryRecord;
import com.parking.entity.ExitRecord;
import com.parking.entity.ParkingLot;
import com.parking.entity.SettlementRecord;
import com.parking.exception.ParkingException;
import com.parking.repository.ExitRecordRepository;
import com.parking.service.ExitService.ExitProcessingResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("出场模块单元测试 - 费用结算异步化")
class ExitServiceTest {

    @Mock
    private ExitRecordRepository exitRecordRepository;

    @Mock
    private EntryService entryService;

    @Mock
    private ParkingSpaceService parkingSpaceService;

    @Mock
    private VehicleService vehicleService;

    @Mock
    private SettlementService settlementService;

    @Mock
    private StatisticsService statisticsService;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private ExitService exitService;

    @Test
    @DisplayName("测试出场处理 - 成功出场")
    void testProcessExit_Success() {
        ParkingLot parkingLot = TestDataBuilder.parkingLotBuilder()
                .parkingId("parking_001")
                .hourlyRate(10.0)
                .build();

        EntryRecord entryRecord = TestDataBuilder.entryRecordBuilder()
                .entryId("entry_001")
                .parkingId("parking_001")
                .entryTime(LocalDateTime.now().minusHours(2))
                .entryStatus("parked")
                .build();

        ExitRequest request = TestDataBuilder.exitRequestBuilder()
                .entryId("entry_001")
                .build();

        SettlementRecord settlement = TestDataBuilder.settlementRecordBuilder()
                .settlementId("settlement_001")
                .entryId("entry_001")
                .parkingFee(20.0)
                .paymentStatus("pending")
                .build();

        when(entryService.getEntryById("entry_001")).thenReturn(entryRecord);
        when(settlementService.createSettlement(eq(entryRecord), any(ExitRecord.class))).thenReturn(settlement);
        when(exitRecordRepository.save(any(ExitRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(entryService).updateEntryStatus("entry_001", "exited");
        doNothing().when(parkingSpaceService).updateSpaceStatus(anyString(), eq("available"));
        doNothing().when(statisticsService).incrementExitCount();
        doNothing().when(historyService).recordExit(any(ExitRecord.class), eq(entryRecord));

        ExitResponse response = exitService.processExit(request);

        assertNotNull(response);
        assertNotNull(response.getExitId());
        assertEquals(20.0, response.getFee(), 0.01);
        assertNotNull(response.getSettlementId());
        assertEquals("settlement_001", response.getSettlementId());
        verify(exitRecordRepository, times(1)).save(any(ExitRecord.class));
    }

    @Test
    @DisplayName("测试出场处理 - 车辆已出场时拒绝重复处理")
    void testProcessExit_AlreadyExited() {
        EntryRecord entryRecord = TestDataBuilder.entryRecordBuilder()
                .entryId("entry_001")
                .entryStatus("exited")
                .build();

        ExitRequest request = TestDataBuilder.exitRequestBuilder()
                .entryId("entry_001")
                .build();

        when(entryService.getEntryById("entry_001")).thenReturn(entryRecord);

        ParkingException exception = assertThrows(ParkingException.class, () -> {
            exitService.processExit(request);
        });

        assertEquals(400, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("已出场"));
    }

    @Test
    @DisplayName("测试出场处理 - 入场ID为空时抛出异常")
    void testProcessExit_NullEntryId() {
        ExitRequest request = new ExitRequest();
        request.setEntryId(null);

        ParkingException exception = assertThrows(ParkingException.class, () -> {
            exitService.processExit(request);
        });

        assertEquals(400, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("入场ID不能为空"));
    }

    @Test
    @DisplayName("测试出场处理 - 入场记录不存在时抛出异常")
    void testProcessExit_EntryNotFound() {
        ExitRequest request = TestDataBuilder.exitRequestBuilder()
                .entryId("nonexistent")
                .build();

        when(entryService.getEntryById("nonexistent")).thenThrow(
                new ParkingException(404, "入场记录不存在: nonexistent"));

        ParkingException exception = assertThrows(ParkingException.class, () -> {
            exitService.processExit(request);
        });

        assertEquals(404, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("入场记录不存在"));
    }

    @Test
    @DisplayName("测试异步出场 - 出场请求提交后立即返回响应不阻塞")
    void testProcessExitAsync_ImmediateResponse() throws InterruptedException {
        EntryRecord entryRecord = TestDataBuilder.entryRecordBuilder()
                .entryId("entry_001")
                .entryTime(LocalDateTime.now().minusHours(2))
                .entryStatus("parked")
                .build();

        ExitRequest request = TestDataBuilder.exitRequestBuilder()
                .entryId("entry_001")
                .build();

        when(entryService.getEntryById("entry_001")).thenReturn(entryRecord);
        doNothing().when(entryService).updateEntryStatus("entry_001", "exiting");
        doNothing().when(parkingSpaceService).updateSpaceStatus(anyString(), eq("available"));
        doNothing().when(statisticsService).incrementExitCount();
        doNothing().when(settlementService).retryPayment(anyString());

        long startTime = System.currentTimeMillis();
        ExitResponse response = exitService.processExitAsync(request);
        long elapsedTime = System.currentTimeMillis() - startTime;

        assertNotNull(response);
        assertNotNull(response.getExitId());
        assertEquals(0.0, response.getFee(), 0.01);
        assertNull(response.getSettlementId());
        assertTrue(elapsedTime < 500, "异步处理应该立即返回，实际耗时: " + elapsedTime + "ms");
    }

    @Test
    @DisplayName("测试异步出场 - 结算状态为processing")
    void testProcessExitAsync_ProcessingStatus() {
        EntryRecord entryRecord = TestDataBuilder.entryRecordBuilder()
                .entryId("entry_async_001")
                .entryTime(LocalDateTime.now().minusHours(2))
                .entryStatus("parked")
                .build();

        ExitRequest request = TestDataBuilder.exitRequestBuilder()
                .entryId("entry_async_001")
                .build();

        when(entryService.getEntryById("entry_async_001")).thenReturn(entryRecord);
        doNothing().when(entryService).updateEntryStatus("entry_async_001", "exiting");
        doNothing().when(parkingSpaceService).updateSpaceStatus(anyString(), eq("available"));
        doNothing().when(statisticsService).incrementExitCount();

        exitService.processExitAsync(request);

        String status = exitService.getAsyncSettlementStatus("entry_async_001");
        assertEquals("processing", status);
        assertFalse(exitService.isSettlementCompleted("entry_async_001"));
    }

    @Test
    @DisplayName("测试异步出场 - 获取处理结果")
    void testGetProcessingResult_Success() {
        EntryRecord entryRecord = TestDataBuilder.entryRecordBuilder()
                .entryId("entry_result_001")
                .entryTime(LocalDateTime.now().minusHours(2))
                .entryStatus("parked")
                .build();

        ExitRequest request = TestDataBuilder.exitRequestBuilder()
                .entryId("entry_result_001")
                .build();

        when(entryService.getEntryById("entry_result_001")).thenReturn(entryRecord);
        doNothing().when(entryService).updateEntryStatus("entry_result_001", "exiting");
        doNothing().when(parkingSpaceService).updateSpaceStatus(anyString(), eq("available"));
        doNothing().when(statisticsService).incrementExitCount();

        exitService.processExitAsync(request);

        ExitProcessingResult result = exitService.getProcessingResult("entry_result_001");

        assertNotNull(result);
        assertNotNull(result.getExitId());
        assertEquals("processing", result.getSettlementStatus());
        assertTrue(result.getParkingDuration() > 0);
    }

    @Test
    @DisplayName("测试异步出场 - 重复出场检测")
    void testProcessExitAsync_AlreadyExited() {
        EntryRecord entryRecord = TestDataBuilder.entryRecordBuilder()
                .entryId("entry_exited_001")
                .entryStatus("exited")
                .build();

        ExitRequest request = TestDataBuilder.exitRequestBuilder()
                .entryId("entry_exited_001")
                .build();

        when(entryService.getEntryById("entry_exited_001")).thenReturn(entryRecord);

        ParkingException exception = assertThrows(ParkingException.class, () -> {
            exitService.processExitAsync(request);
        });

        assertEquals(400, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("已出场"));
    }

    @Test
    @DisplayName("测试出场状态变更 - 从parked到exited")
    void testProcessExit_StatusChange() {
        EntryRecord entryRecord = TestDataBuilder.entryRecordBuilder()
                .entryId("entry_status_001")
                .entryStatus("parked")
                .build();

        ExitRequest request = TestDataBuilder.exitRequestBuilder()
                .entryId("entry_status_001")
                .build();

        SettlementRecord settlement = TestDataBuilder.settlementRecordBuilder()
                .settlementId("settlement_001")
                .parkingFee(20.0)
                .build();

        when(entryService.getEntryById("entry_status_001")).thenReturn(entryRecord);
        when(settlementService.createSettlement(eq(entryRecord), any(ExitRecord.class))).thenReturn(settlement);
        when(exitRecordRepository.save(any(ExitRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        exitService.processExit(request);

        verify(entryService, times(1)).updateEntryStatus("entry_status_001", "exited");
    }

    @Test
    @DisplayName("测试出场后车位状态 - 从occupied到available")
    void testProcessExit_SpaceStatusChange() {
        EntryRecord entryRecord = TestDataBuilder.entryRecordBuilder()
                .entryId("entry_space_001")
                .spaceId("space_001")
                .entryStatus("parked")
                .build();

        ExitRequest request = TestDataBuilder.exitRequestBuilder()
                .entryId("entry_space_001")
                .build();

        SettlementRecord settlement = TestDataBuilder.settlementRecordBuilder()
                .settlementId("settlement_001")
                .build();

        when(entryService.getEntryById("entry_space_001")).thenReturn(entryRecord);
        when(settlementService.createSettlement(eq(entryRecord), any(ExitRecord.class))).thenReturn(settlement);
        when(exitRecordRepository.save(any(ExitRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        exitService.processExit(request);

        verify(parkingSpaceService, times(1)).updateSpaceStatus("space_001", "available");
    }

    @Test
    @DisplayName("测试出场后车辆状态 - 从parked到idle")
    void testProcessExit_VehicleStatusChange() {
        EntryRecord entryRecord = TestDataBuilder.entryRecordBuilder()
                .entryId("entry_vehicle_001")
                .vehicleId("vehicle_001")
                .entryStatus("parked")
                .build();

        ExitRequest request = TestDataBuilder.exitRequestBuilder()
                .entryId("entry_vehicle_001")
                .build();

        SettlementRecord settlement = TestDataBuilder.settlementRecordBuilder()
                .settlementId("settlement_001")
                .build();

        when(entryService.getEntryById("entry_vehicle_001")).thenReturn(entryRecord);
        when(settlementService.createSettlement(eq(entryRecord), any(ExitRecord.class))).thenReturn(settlement);
        when(exitRecordRepository.save(any(ExitRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        exitService.processExit(request);

        verify(vehicleService, times(1)).updateVehicleStatus("vehicle_001", "idle");
    }

    @Test
    @DisplayName("测试出场统计更新 - 出场计数增加")
    void testProcessExit_StatisticsUpdate() {
        EntryRecord entryRecord = TestDataBuilder.entryRecordBuilder()
                .entryId("entry_stats_001")
                .entryStatus("parked")
                .build();

        ExitRequest request = TestDataBuilder.exitRequestBuilder()
                .entryId("entry_stats_001")
                .build();

        SettlementRecord settlement = TestDataBuilder.settlementRecordBuilder()
                .settlementId("settlement_001")
                .build();

        when(entryService.getEntryById("entry_stats_001")).thenReturn(entryRecord);
        when(settlementService.createSettlement(eq(entryRecord), any(ExitRecord.class))).thenReturn(settlement);
        when(exitRecordRepository.save(any(ExitRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        exitService.processExit(request);

        verify(statisticsService, times(1)).incrementExitCount();
    }

    @Test
    @DisplayName("测试获取出场记录 - 成功获取")
    void testGetExitById_Success() {
        ExitRecord exitRecord = TestDataBuilder.exitRecordBuilder()
                .exitId("exit_001")
                .entryId("entry_001")
                .parkingDuration(120)
                .parkingFee(20.0)
                .build();

        when(exitRecordRepository.findByExitId("exit_001")).thenReturn(Optional.of(exitRecord));

        ExitRecord result = exitService.getExitById("exit_001");

        assertNotNull(result);
        assertEquals("exit_001", result.getExitId());
        assertEquals("entry_001", result.getEntryId());
        assertEquals(120, result.getParkingDuration());
        assertEquals(20.0, result.getParkingFee(), 0.01);
    }

    @Test
    @DisplayName("测试获取出场记录 - 记录不存在时抛出异常")
    void testGetExitById_NotFound() {
        when(exitRecordRepository.findByExitId("nonexistent")).thenReturn(Optional.empty());

        ParkingException exception = assertThrows(ParkingException.class, () -> {
            exitService.getExitById("nonexistent");
        });

        assertEquals(404, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("出场记录不存在"));
    }

    @Test
    @DisplayName("测试通过入场ID获取出场记录 - 成功获取")
    void testGetExitByEntryId_Success() {
        ExitRecord exitRecord = TestDataBuilder.exitRecordBuilder()
                .exitId("exit_001")
                .entryId("entry_001")
                .build();

        when(exitRecordRepository.findByEntryId("entry_001")).thenReturn(Optional.of(exitRecord));

        ExitRecord result = exitService.getExitByEntryId("entry_001");

        assertNotNull(result);
        assertEquals("entry_001", result.getEntryId());
    }

    @Test
    @DisplayName("测试短时停车计算时长 - 停车时长计算正确")
    void testProcessExit_ShortParkingDuration() {
        EntryRecord entryRecord = TestDataBuilder.entryRecordBuilder()
                .entryId("entry_short_001")
                .entryTime(LocalDateTime.now().minusMinutes(30))
                .entryStatus("parked")
                .build();

        ExitRequest request = TestDataBuilder.exitRequestBuilder()
                .entryId("entry_short_001")
                .build();

        SettlementRecord settlement = TestDataBuilder.settlementRecordBuilder()
                .settlementId("settlement_001")
                .parkingFee(10.0)
                .build();

        when(entryService.getEntryById("entry_short_001")).thenReturn(entryRecord);
        when(settlementService.createSettlement(eq(entryRecord), any(ExitRecord.class))).thenReturn(settlement);
        when(exitRecordRepository.save(any(ExitRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ExitResponse response = exitService.processExit(request);

        assertTrue(response.getParkingDuration() >= 30);
        assertEquals(10.0, response.getFee(), 0.01);
    }

    @Test
    @DisplayName("测试长时停车计算时长 - 停车时长计算正确")
    void testProcessExit_LongParkingDuration() {
        EntryRecord entryRecord = TestDataBuilder.entryRecordBuilder()
                .entryId("entry_long_001")
                .entryTime(LocalDateTime.now().minusHours(5))
                .entryStatus("parked")
                .build();

        ExitRequest request = TestDataBuilder.exitRequestBuilder()
                .entryId("entry_long_001")
                .build();

        SettlementRecord settlement = TestDataBuilder.settlementRecordBuilder()
                .settlementId("settlement_001")
                .parkingFee(50.0)
                .build();

        when(entryService.getEntryById("entry_long_001")).thenReturn(entryRecord);
        when(settlementService.createSettlement(eq(entryRecord), any(ExitRecord.class))).thenReturn(settlement);
        when(exitRecordRepository.save(any(ExitRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ExitResponse response = exitService.processExit(request);

        assertTrue(response.getParkingDuration() >= 300);
        assertEquals(50.0, response.getFee(), 0.01);
    }
}
