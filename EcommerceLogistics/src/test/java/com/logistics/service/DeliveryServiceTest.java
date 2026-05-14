package com.logistics.service;

import com.logistics.builder.TestDataBuilder;
import com.logistics.constant.LogisticsConstants;
import com.logistics.dto.*;
import com.logistics.entity.Courier;
import com.logistics.entity.Station;
import com.logistics.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class DeliveryServiceTest {

    @Autowired
    private LogisticsService logisticsService;

    @Autowired
    private DeliveryService deliveryService;

    @Autowired
    private StationService stationService;

    @Autowired
    private CourierService courierService;

    @Autowired
    private LogisticsRepository logisticsRepository;

    @Autowired
    private DeliveryTaskRepository deliveryTaskRepository;

    @Autowired
    private StationRepository stationRepository;

    @Autowired
    private CourierRepository courierRepository;

    @BeforeEach
    void setUp() {
        logisticsRepository.deleteAll();
        deliveryTaskRepository.deleteAll();
        courierRepository.deleteAll();
        stationRepository.deleteAll();

        Station station = TestDataBuilder.buildTestStation("station_test_001", "测试网点");
        stationService.createStation(station);

        Courier courier = TestDataBuilder.buildTestCourier("courier_test_001", "station_test_001", "测试员");
        courierService.createCourier(courier);
    }

    @Test
    void testAssignTask_Success() {
        CreateLogisticsRequest createRequest = TestDataBuilder.buildCreateLogisticsRequest("order_001", "station_test_001");
        CreateLogisticsResponse logisticsResponse = logisticsService.createLogistics(createRequest);

        AssignTaskRequest assignRequest = TestDataBuilder.buildAssignTaskRequest(logisticsResponse.getLogisticsId(), "courier_test_001");
        AssignTaskResponse assignResponse = deliveryService.assignTask(assignRequest);

        assertNotNull(assignResponse);
        assertNotNull(assignResponse.getTaskId());
        assertEquals(LogisticsConstants.TASK_STATUS_ASSIGNED, assignResponse.getStatus());
    }

    @Test
    void testStartDelivery_Success() {
        CreateLogisticsRequest createRequest = TestDataBuilder.buildCreateLogisticsRequest("order_002", "station_test_001");
        CreateLogisticsResponse logisticsResponse = logisticsService.createLogistics(createRequest);

        AssignTaskRequest assignRequest = TestDataBuilder.buildAssignTaskRequest(logisticsResponse.getLogisticsId(), "courier_test_001");
        AssignTaskResponse assignResponse = deliveryService.assignTask(assignRequest);

        UpdateTaskRequest updateRequest = TestDataBuilder.buildUpdateTaskRequest(assignResponse.getTaskId(), LogisticsConstants.ACTION_START);
        UpdateTaskResponse updateResponse = deliveryService.updateTask(updateRequest);

        assertNotNull(updateResponse);
        assertEquals(LogisticsConstants.TASK_STATUS_DELIVERING, updateResponse.getStatus());
    }

    @Test
    void testCompleteDelivery_Success() {
        CreateLogisticsRequest createRequest = TestDataBuilder.buildCreateLogisticsRequest("order_003", "station_test_001");
        CreateLogisticsResponse logisticsResponse = logisticsService.createLogistics(createRequest);

        AssignTaskRequest assignRequest = TestDataBuilder.buildAssignTaskRequest(logisticsResponse.getLogisticsId(), "courier_test_001");
        AssignTaskResponse assignResponse = deliveryService.assignTask(assignRequest);

        UpdateTaskRequest startRequest = TestDataBuilder.buildUpdateTaskRequest(assignResponse.getTaskId(), LogisticsConstants.ACTION_START);
        deliveryService.updateTask(startRequest);

        UpdateTaskRequest updateRequest = TestDataBuilder.buildUpdateTaskRequest(assignResponse.getTaskId(), LogisticsConstants.ACTION_UPDATE, "北京市朝阳区", "已到达小区门口");
        deliveryService.updateTask(updateRequest);

        UpdateTaskRequest completeRequest = TestDataBuilder.buildUpdateTaskRequest(assignResponse.getTaskId(), LogisticsConstants.ACTION_COMPLETE, "北京市朝阳区收货人地址", null);
        UpdateTaskResponse completeResponse = deliveryService.updateTask(completeRequest);

        assertNotNull(completeResponse);
        assertEquals(LogisticsConstants.TASK_STATUS_COMPLETED, completeResponse.getStatus());
        assertTrue(completeResponse.getMessage().contains("配送已完成"));
    }
}
