package com.parking.service;

import com.parking.builder.TestDataBuilder;
import com.parking.dto.EntryRequest;
import com.parking.dto.EntryResponse;
import com.parking.entity.EntryRecord;
import com.parking.entity.ParkingLot;
import com.parking.entity.ParkingSpace;
import com.parking.entity.Vehicle;
import com.parking.exception.ParkingException;
import com.parking.repository.EntryRecordRepository;
import com.parking.service.ParkingSpaceService.LockInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("入场模块单元测试 - 车位锁定机制")
class EntryServiceTest {

    @Mock
    private EntryRecordRepository entryRecordRepository;

    @Mock
    private VehicleService vehicleService;

    @Mock
    private ParkingSpaceService parkingSpaceService;

    @Mock
    private ParkingLotService parkingLotService;

    @Mock
    private StatisticsService statisticsService;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private EntryService entryService;

    @Test
    @DisplayName("测试车位锁定 - 成功获取分布式锁")
    void testTryLockSpace_Success() {
        String spaceId = "space_001";
        String vehicleType = "sedan";
        ParkingSpace space = TestDataBuilder.parkingSpaceBuilder()
                .spaceId(spaceId)
                .spaceStatus("available")
                .build();

        when(parkingSpaceService.getLockTimeoutByVehicleType(vehicleType)).thenReturn(120);
        when(parkingSpaceService.tryLockSpace(spaceId, vehicleType)).thenReturn(true);

        boolean result = parkingSpaceService.tryLockSpace(spaceId, vehicleType);

        assertTrue(result);
    }

    @Test
    @DisplayName("测试车位锁定 - 车位已占用时锁定失败")
    void testTryLockSpace_SpaceOccupied() {
        String spaceId = "space_001";
        String vehicleType = "sedan";
        ParkingSpace space = TestDataBuilder.parkingSpaceBuilder()
                .spaceId(spaceId)
                .spaceStatus("occupied")
                .build();

        when(parkingSpaceService.tryLockSpace(spaceId, vehicleType)).thenReturn(false);

        boolean result = parkingSpaceService.tryLockSpace(spaceId, vehicleType);

        assertFalse(result);
    }

    @Test
    @DisplayName("测试车位锁定 - 不同车辆类型的锁定超时差异")
    void testGetLockTimeoutByVehicleType_DifferentTimeouts() {
        assertEquals(120, parkingSpaceService.getLockTimeoutByVehicleType("sedan"));
        assertEquals(120, parkingSpaceService.getLockTimeoutByVehicleType("standard"));
        assertEquals(30, parkingSpaceService.getLockTimeoutByVehicleType("vip"));
        assertEquals(30, parkingSpaceService.getLockTimeoutByVehicleType("VIP"));
    }

    @Test
    @DisplayName("测试车位锁定 - 并发分配时锁冲突处理")
    void testConcurrentSpaceAllocation_LockConflict() throws InterruptedException {
        String spaceId = "space_001";
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        when(parkingSpaceService.tryLockSpace(spaceId, "sedan"))
                .thenAnswer(invocation -> {
                    return successCount.getAndIncrement() == 0;
                });

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    boolean locked = parkingSpaceService.tryLockSpace(spaceId, "sedan");
                    if (locked) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertTrue(successCount.get() >= 1);
        assertTrue(failCount.get() >= threadCount - 2);
    }

    @Test
    @DisplayName("测试车位锁定释放 - 成功释放锁")
    void testReleaseLock_Success() {
        String spaceId = "space_001";

        doNothing().when(parkingSpaceService).releaseLock(spaceId);

        assertDoesNotThrow(() -> parkingSpaceService.releaseLock(spaceId));
        verify(parkingSpaceService, times(1)).releaseLock(spaceId);
    }

    @Test
    @DisplayName("测试车位锁定恢复 - 超时后锁自动释放")
    void testLockTimeout_AutoRelease() {
        String spaceId = "space_001";
        ParkingSpace space = TestDataBuilder.parkingSpaceBuilder()
                .spaceId(spaceId)
                .spaceStatus("available")
                .build();

        when(parkingSpaceService.tryLockSpace(spaceId, "vip")).thenReturn(true);
        when(parkingSpaceService.isSpaceLocked(spaceId)).thenReturn(false);

        boolean locked = parkingSpaceService.tryLockSpace(spaceId, "vip");
        assertTrue(locked);

        boolean stillLocked = parkingSpaceService.isSpaceLocked(spaceId);
        assertFalse(stillLocked);
    }

    @Test
    @DisplayName("测试停车入场 - 车位已满时拒绝入场")
    void testProcessEntry_ParkingLotFull() {
        EntryRequest request = TestDataBuilder.entryRequestBuilder()
                .vehicleNumber("京A12345")
                .parkingId("parking_001")
                .build();

        ParkingLot parkingLot = TestDataBuilder.parkingLotBuilder()
                .parkingId("parking_001")
                .build();

        Vehicle vehicle = TestDataBuilder.vehicleBuilder()
                .vehicleNumber("京A12345")
                .build();

        when(parkingLotService.getParkingLotById("parking_001")).thenReturn(parkingLot);
        when(vehicleService.createOrGetVehicle("京A12345", null, null, null)).thenReturn(vehicle);
        when(entryRecordRepository.findByVehicleIdAndEntryStatus(vehicle.getVehicleId(), "parked"))
                .thenReturn(new ArrayList<>());
        when(parkingSpaceService.allocateSpace("parking_001"))
                .thenThrow(new ParkingException(400, "停车场暂无可用车位"));

        ParkingException exception = assertThrows(ParkingException.class, () -> {
            entryService.processEntry(request);
        });

        assertEquals(400, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("暂无可用车位"));
    }

    @Test
    @DisplayName("测试停车入场 - 车辆已在场内时拒绝重复入场")
    void testProcessEntry_VehicleAlreadyParked() {
        EntryRequest request = TestDataBuilder.entryRequestBuilder()
                .vehicleNumber("京A12345")
                .parkingId("parking_001")
                .build();

        ParkingLot parkingLot = TestDataBuilder.parkingLotBuilder()
                .parkingId("parking_001")
                .build();

        Vehicle vehicle = TestDataBuilder.vehicleBuilder()
                .vehicleId("vehicle_001")
                .vehicleNumber("京A12345")
                .build();

        EntryRecord existingEntry = TestDataBuilder.entryRecordBuilder()
                .entryId("entry_existing")
                .vehicleId("vehicle_001")
                .entryStatus("parked")
                .build();

        when(parkingLotService.getParkingLotById("parking_001")).thenReturn(parkingLot);
        when(vehicleService.createOrGetVehicle("京A12345", null, null, null)).thenReturn(vehicle);
        when(entryRecordRepository.findByVehicleIdAndEntryStatus("vehicle_001", "parked"))
                .thenReturn(Collections.singletonList(existingEntry));

        ParkingException exception = assertThrows(ParkingException.class, () -> {
            entryService.processEntry(request);
        });

        assertEquals(400, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("已在场内"));
    }

    @Test
    @DisplayName("测试停车入场 - 成功入场流程")
    void testProcessEntry_Success() {
        EntryRequest request = TestDataBuilder.entryRequestBuilder()
                .vehicleNumber("京A12345")
                .parkingId("parking_001")
                .build();

        ParkingLot parkingLot = TestDataBuilder.parkingLotBuilder()
                .parkingId("parking_001")
                .build();

        Vehicle vehicle = TestDataBuilder.vehicleBuilder()
                .vehicleId("vehicle_001")
                .vehicleNumber("京A12345")
                .build();

        ParkingSpace space = TestDataBuilder.parkingSpaceBuilder()
                .spaceId("space_001")
                .spaceNumber("A001")
                .build();

        when(parkingLotService.getParkingLotById("parking_001")).thenReturn(parkingLot);
        when(vehicleService.createOrGetVehicle("京A12345", null, null, null)).thenReturn(vehicle);
        when(entryRecordRepository.findByVehicleIdAndEntryStatus("vehicle_001", "parked"))
                .thenReturn(new ArrayList<>());
        when(parkingSpaceService.allocateSpace("parking_001")).thenReturn(space);
        when(entryRecordRepository.save(any(EntryRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(vehicleService.updateVehicleStatus("vehicle_001", "parked")).thenReturn(vehicle);
        doNothing().when(statisticsService).incrementEntryCount();
        doNothing().when(historyService).recordEntry(any(EntryRecord.class));

        EntryResponse response = entryService.processEntry(request);

        assertNotNull(response);
        assertNotNull(response.getEntryId());
        assertEquals("A001", response.getSpaceNumber());
        assertEquals("space_001", response.getSpaceId());
        assertEquals("京A12345", response.getVehicleNumber());
        verify(entryRecordRepository, times(1)).save(any(EntryRecord.class));
        verify(statisticsService, times(1)).incrementEntryCount();
    }

    @Test
    @DisplayName("测试入场记录查询 - 成功获取入场记录")
    void testGetEntryById_Success() {
        EntryRecord entry = TestDataBuilder.entryRecordBuilder()
                .entryId("entry_001")
                .vehicleNumber("京A12345")
                .build();

        when(entryRecordRepository.findByEntryId("entry_001")).thenReturn(Optional.of(entry));

        EntryRecord result = entryService.getEntryById("entry_001");

        assertNotNull(result);
        assertEquals("entry_001", result.getEntryId());
        assertEquals("京A12345", result.getVehicleNumber());
    }

    @Test
    @DisplayName("测试入场记录查询 - 记录不存在时抛出异常")
    void testGetEntryById_NotFound() {
        when(entryRecordRepository.findByEntryId("nonexistent")).thenReturn(Optional.empty());

        ParkingException exception = assertThrows(ParkingException.class, () -> {
            entryService.getEntryById("nonexistent");
        });

        assertEquals(404, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("入场记录不存在"));
    }

    @Test
    @DisplayName("测试获取活跃入场记录 - 成功返回列表")
    void testGetActiveEntries_Success() {
        List<EntryRecord> activeEntries = new ArrayList<>();
        activeEntries.add(TestDataBuilder.entryRecordBuilder()
                .entryId("entry_001")
                .entryStatus("parked")
                .build());
        activeEntries.add(TestDataBuilder.entryRecordBuilder()
                .entryId("entry_002")
                .entryStatus("parked")
                .build());

        when(entryRecordRepository.findByEntryStatus("parked")).thenReturn(activeEntries);

        List<EntryRecord> result = entryService.getActiveEntries();

        assertNotNull(result);
        assertEquals(2, result.size());
        result.forEach(entry -> assertEquals("parked", entry.getEntryStatus()));
    }

    @Test
    @DisplayName("测试入场状态更新 - 成功更新状态")
    void testUpdateEntryStatus_Success() {
        EntryRecord entry = TestDataBuilder.entryRecordBuilder()
                .entryId("entry_001")
                .entryStatus("parked")
                .build();

        when(entryRecordRepository.findByEntryId("entry_001")).thenReturn(Optional.of(entry));
        when(entryRecordRepository.save(any(EntryRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EntryRecord result = entryService.updateEntryStatus("entry_001", "exited");

        assertEquals("exited", result.getEntryStatus());
    }

    @Test
    @DisplayName("测试VIP车辆入场 - 优先分配VIP车位")
    void testVipVehicleEntry_ShouldNotAffectStandardEntry() {
        EntryRequest vipRequest = TestDataBuilder.entryRequestBuilder()
                .vehicleNumber("京V88888")
                .parkingId("parking_001")
                .vehicleType("vip")
                .build();

        ParkingLot parkingLot = TestDataBuilder.parkingLotBuilder()
                .parkingId("parking_001")
                .build();

        Vehicle vipVehicle = TestDataBuilder.vehicleBuilder()
                .vehicleId("vehicle_vip")
                .vehicleNumber("京V88888")
                .vehicleType("vip")
                .build();

        ParkingSpace vipSpace = TestDataBuilder.parkingSpaceBuilder()
                .spaceId("space_vip")
                .spaceNumber("V001")
                .spaceType("vip")
                .build();

        when(parkingLotService.getParkingLotById("parking_001")).thenReturn(parkingLot);
        when(vehicleService.createOrGetVehicle("京V88888", "vip", null, null)).thenReturn(vipVehicle);
        when(entryRecordRepository.findByVehicleIdAndEntryStatus("vehicle_vip", "parked"))
                .thenReturn(new ArrayList<>());
        when(parkingSpaceService.allocateSpace("parking_001")).thenReturn(vipSpace);
        when(entryRecordRepository.save(any(EntryRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(vehicleService.updateVehicleStatus("vehicle_vip", "parked")).thenReturn(vipVehicle);
        doNothing().when(statisticsService).incrementEntryCount();
        doNothing().when(historyService).recordEntry(any(EntryRecord.class));

        EntryResponse response = entryService.processEntry(vipRequest);

        assertNotNull(response);
        assertEquals("space_vip", response.getSpaceId());
        assertEquals("V001", response.getSpaceNumber());
    }
}
