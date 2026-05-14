package com.parking.service;

import com.parking.builder.TestDataBuilder;
import com.parking.entity.ParkingLot;
import com.parking.entity.ParkingSpace;
import com.parking.exception.ParkingException;
import com.parking.repository.ParkingSpaceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("车位管理模块单元测试")
class ParkingSpaceServiceTest {

    @Mock
    private ParkingSpaceRepository parkingSpaceRepository;

    @Mock
    private ParkingLotService parkingLotService;

    @InjectMocks
    private ParkingSpaceService parkingSpaceService;

    @Test
    @DisplayName("测试创建车位 - 成功创建标准车位")
    void testCreateParkingSpace_Success() {
        ParkingLot parkingLot = TestDataBuilder.parkingLotBuilder()
                .parkingId("parking_001")
                .name("测试停车场")
                .build();

        when(parkingLotService.getParkingLotById("parking_001")).thenReturn(parkingLot);
        when(parkingSpaceRepository.save(any(ParkingSpace.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ParkingSpace result = parkingSpaceService.createParkingSpace("parking_001", "A001", "standard", 10.0);

        assertNotNull(result);
        assertEquals("A001", result.getSpaceNumber());
        assertEquals("standard", result.getSpaceType());
        assertEquals(10.0, result.getSpacePrice());
        assertEquals("available", result.getSpaceStatus());
        verify(parkingSpaceRepository, times(1)).save(any(ParkingSpace.class));
    }

    @Test
    @DisplayName("测试创建车位 - 停车场不存在时抛出异常")
    void testCreateParkingSpace_ParkingLotNotFound() {
        when(parkingLotService.getParkingLotById("nonexistent")).thenThrow(
                new ParkingException(404, "停车场不存在: nonexistent"));

        assertThrows(ParkingException.class, () -> {
            parkingSpaceService.createParkingSpace("nonexistent", "A001", "standard", 10.0);
        });
    }

    @Test
    @DisplayName("测试获取车位信息 - 成功获取")
    void testGetParkingSpaceById_Success() {
        ParkingSpace space = TestDataBuilder.parkingSpaceBuilder()
                .spaceId("space_001")
                .spaceNumber("A001")
                .build();

        when(parkingSpaceRepository.findBySpaceId("space_001")).thenReturn(Optional.of(space));

        ParkingSpace result = parkingSpaceService.getParkingSpaceById("space_001");

        assertNotNull(result);
        assertEquals("space_001", result.getSpaceId());
        assertEquals("A001", result.getSpaceNumber());
    }

    @Test
    @DisplayName("测试获取车位信息 - 车位不存在时抛出异常")
    void testGetParkingSpaceById_NotFound() {
        when(parkingSpaceRepository.findBySpaceId("nonexistent")).thenReturn(Optional.empty());

        ParkingException exception = assertThrows(ParkingException.class, () -> {
            parkingSpaceService.getParkingSpaceById("nonexistent");
        });

        assertEquals(404, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("车位不存在"));
    }

    @Test
    @DisplayName("测试获取可用车位列表 - 成功返回列表")
    void testGetAvailableSpaces_Success() {
        ParkingLot parkingLot = TestDataBuilder.parkingLotBuilder().build();
        List<ParkingSpace> availableSpaces = TestDataBuilder.parkingSpaceBuilder()
                .buildAvailableSpaces(5, parkingLot);

        when(parkingSpaceRepository.findAvailableSpacesByParkingId(parkingLot.getParkingId()))
                .thenReturn(availableSpaces);

        List<ParkingSpace> result = parkingSpaceService.getAvailableSpaces(parkingLot.getParkingId());

        assertNotNull(result);
        assertEquals(5, result.size());
        result.forEach(space -> assertEquals("available", space.getSpaceStatus()));
    }

    @Test
    @DisplayName("测试车位分配 - 成功分配第一个可用车位")
    void testAllocateSpace_Success() {
        ParkingLot parkingLot = TestDataBuilder.parkingLotBuilder().build();
        List<ParkingSpace> availableSpaces = TestDataBuilder.parkingSpaceBuilder()
                .buildAvailableSpaces(3, parkingLot);
        ParkingSpace firstSpace = availableSpaces.get(0);

        when(parkingSpaceRepository.findAvailableSpacesByParkingId(parkingLot.getParkingId()))
                .thenReturn(availableSpaces);
        when(parkingSpaceRepository.save(any(ParkingSpace.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ParkingSpace result = parkingSpaceService.allocateSpace(parkingLot.getParkingId());

        assertNotNull(result);
        assertEquals(firstSpace.getSpaceId(), result.getSpaceId());
        assertEquals("occupied", result.getSpaceStatus());
        assertNotNull(result.getOccupiedTime());
    }

    @Test
    @DisplayName("测试车位分配 - 无可用车位时抛出异常")
    void testAllocateSpace_NoAvailableSpaces() {
        ParkingLot parkingLot = TestDataBuilder.parkingLotBuilder().build();

        when(parkingSpaceRepository.findAvailableSpacesByParkingId(parkingLot.getParkingId()))
                .thenReturn(new ArrayList<>());

        ParkingException exception = assertThrows(ParkingException.class, () -> {
            parkingSpaceService.allocateSpace(parkingLot.getParkingId());
        });

        assertEquals(400, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("暂无可用车位"));
    }

    @Test
    @DisplayName("测试车位状态流转 - 空闲到已占用")
    void testUpdateSpaceStatus_AvailableToOccupied() {
        ParkingSpace space = TestDataBuilder.parkingSpaceBuilder()
                .spaceId("space_001")
                .spaceStatus("available")
                .build();

        when(parkingSpaceRepository.findBySpaceId("space_001")).thenReturn(Optional.of(space));
        when(parkingSpaceRepository.save(any(ParkingSpace.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ParkingSpace result = parkingSpaceService.updateSpaceStatus("space_001", "occupied");

        assertEquals("occupied", result.getSpaceStatus());
        assertNotNull(result.getOccupiedTime());
    }

    @Test
    @DisplayName("测试车位状态流转 - 已占用到空闲")
    void testUpdateSpaceStatus_OccupiedToAvailable() {
        ParkingSpace space = TestDataBuilder.parkingSpaceBuilder()
                .spaceId("space_001")
                .spaceStatus("occupied")
                .build();

        when(parkingSpaceRepository.findBySpaceId("space_001")).thenReturn(Optional.of(space));
        when(parkingSpaceRepository.save(any(ParkingSpace.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ParkingSpace result = parkingSpaceService.updateSpaceStatus("space_001", "available");

        assertEquals("available", result.getSpaceStatus());
        assertNull(result.getOccupiedTime());
    }

    @Test
    @DisplayName("测试车位状态流转 - 空闲到已预约")
    void testUpdateSpaceStatus_AvailableToReserved() {
        ParkingSpace space = TestDataBuilder.parkingSpaceBuilder()
                .spaceId("space_001")
                .spaceStatus("available")
                .build();

        when(parkingSpaceRepository.findBySpaceId("space_001")).thenReturn(Optional.of(space));
        when(parkingSpaceRepository.save(any(ParkingSpace.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ParkingSpace result = parkingSpaceService.updateSpaceStatus("space_001", "reserved");

        assertEquals("reserved", result.getSpaceStatus());
    }

    @Test
    @DisplayName("测试车位分配规则 - 按车位号顺序分配")
    void testAllocateSpace_AllocationOrder() {
        ParkingLot parkingLot = TestDataBuilder.parkingLotBuilder().build();
        List<ParkingSpace> availableSpaces = new ArrayList<>();
        availableSpaces.add(TestDataBuilder.parkingSpaceBuilder().spaceId("space_003").spaceNumber("A003").buildWithParkingLot(parkingLot));
        availableSpaces.add(TestDataBuilder.parkingSpaceBuilder().spaceId("space_001").spaceNumber("A001").buildWithParkingLot(parkingLot));
        availableSpaces.add(TestDataBuilder.parkingSpaceBuilder().spaceId("space_002").spaceNumber("A002").buildWithParkingLot(parkingLot));

        when(parkingSpaceRepository.findAvailableSpacesByParkingId(parkingLot.getParkingId()))
                .thenReturn(availableSpaces);
        when(parkingSpaceRepository.save(any(ParkingSpace.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ParkingSpace firstAllocated = parkingSpaceService.allocateSpace(parkingLot.getParkingId());

        assertEquals("space_003", firstAllocated.getSpaceId());
        assertEquals("A003", firstAllocated.getSpaceNumber());
    }

    @Test
    @DisplayName("测试统计可用车位数 - 正确计算")
    void testCountAvailableSpaces() {
        String parkingId = "parking_001";
        when(parkingSpaceRepository.countAvailableSpaces(parkingId)).thenReturn(15L);

        long count = parkingSpaceService.countAvailableSpaces(parkingId);

        assertEquals(15L, count);
        verify(parkingSpaceRepository, times(1)).countAvailableSpaces(parkingId);
    }

    @Test
    @DisplayName("测试统计总车位数 - 正确计算")
    void testCountTotalSpaces() {
        String parkingId = "parking_001";
        when(parkingSpaceRepository.countTotalSpaces(parkingId)).thenReturn(50L);

        long count = parkingSpaceService.countTotalSpaces(parkingId);

        assertEquals(50L, count);
        verify(parkingSpaceRepository, times(1)).countTotalSpaces(parkingId);
    }

    @Test
    @DisplayName("测试删除车位 - 成功删除")
    void testDeleteParkingSpace_Success() {
        ParkingSpace space = TestDataBuilder.parkingSpaceBuilder()
                .spaceId("space_001")
                .build();

        when(parkingSpaceRepository.findBySpaceId("space_001")).thenReturn(Optional.of(space));
        doNothing().when(parkingSpaceRepository).delete(space);

        assertDoesNotThrow(() -> parkingSpaceService.deleteParkingSpace("space_001"));
        verify(parkingSpaceRepository, times(1)).delete(space);
    }

    @Test
    @DisplayName("测试删除车位 - 车位不存在时抛出异常")
    void testDeleteParkingSpace_NotFound() {
        when(parkingSpaceRepository.findBySpaceId("nonexistent")).thenReturn(Optional.empty());

        assertThrows(ParkingException.class, () -> {
            parkingSpaceService.deleteParkingSpace("nonexistent");
        });
    }
}
