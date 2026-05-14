package com.logistics.service;

import com.logistics.builder.TestDataBuilder;
import com.logistics.constant.LogisticsConstants;
import com.logistics.dto.AssignTaskRequest;
import com.logistics.dto.AssignTaskResponse;
import com.logistics.entity.*;
import com.logistics.exception.LogisticsException;
import com.logistics.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("配送模块测试")
class DeliveryServiceLockTest {

    @Mock
    private DeliveryTaskRepository deliveryTaskRepository;

    @Mock
    private LogisticsService logisticsService;

    @Mock
    private CourierService courierService;

    @Mock
    private StationService stationService;

    @Mock
    private TrackService trackService;

    @Mock
    private AsyncNotificationService statusService;

    @Mock
    private FeeService feeService;

    @Mock
    private StatisticsService statisticsService;

    @Mock
    private HistoryService historyService;

    @Mock
    private CourierLockService courierLockService;

    @Mock
    private DeliveryTypeService deliveryTypeService;

    @InjectMocks
    private DeliveryService deliveryService;

    private Logistics testLogistics;
    private Courier testCourier;
    private DeliveryTask testTask;
    private DeliveryType standardDeliveryType;

    @BeforeEach
    void setUp() {
        testLogistics = TestDataBuilder.buildTestLogistics(
                TestDataBuilder.TEST_ORDER_ID, TestDataBuilder.TEST_STATION_ID);
        testCourier = TestDataBuilder.buildTestCourier();
        testTask = TestDataBuilder.buildTestDeliveryTask(
                testLogistics.getLogisticsId(), testCourier.getCourierId(), testLogistics.getStationId());
        standardDeliveryType = TestDataBuilder.buildStandardDeliveryType();

        when(deliveryTypeService.getDeliveryType(anyString())).thenReturn(standardDeliveryType);
    }

    @Test
    @DisplayName("测试配送员锁定机制在分配任务时的应用")
    void testCourierLockDuringAssignment() {
        AssignTaskRequest request = TestDataBuilder.buildAssignTaskRequest(
                testLogistics.getLogisticsId(), testCourier.getCourierId());

        when(logisticsService.getLogisticsById(anyString())).thenReturn(testLogistics);
        when(deliveryTaskRepository.findByLogisticsId(anyString())).thenReturn(Optional.empty());
        when(courierService.getCourierById(anyString())).thenReturn(testCourier);
        when(courierLockService.tryLock(anyString(), anyString(), eq(CourierLockService.URGENCY_NORMAL)))
                .thenReturn(true);
        when(deliveryTaskRepository.save(any(DeliveryTask.class))).thenReturn(testTask);

        AssignTaskResponse response = deliveryService.assignTask(request);

        assertNotNull(response);
        assertEquals(LogisticsConstants.TASK_STATUS_ASSIGNED, response.getStatus());

        verify(courierLockService, times(1)).tryLock(
                eq(testCourier.getCourierId()),
                eq(testLogistics.getLogisticsId()),
                eq(CourierLockService.URGENCY_NORMAL));
        verify(courierService, times(1)).incrementCourierCurrent(eq(testCourier.getCourierId()));
        verify(courierService, times(1)).updateCourierStatus(
                eq(testCourier.getCourierId()), eq(LogisticsConstants.COURIER_STATUS_BUSY));
    }

    @Test
    @DisplayName("测试配送员锁定失败时的拒绝处理")
    void testCourierLockFailure() {
        AssignTaskRequest request = TestDataBuilder.buildAssignTaskRequest(
                testLogistics.getLogisticsId(), testCourier.getCourierId());

        when(logisticsService.getLogisticsById(anyString())).thenReturn(testLogistics);
        when(deliveryTaskRepository.findByLogisticsId(anyString())).thenReturn(Optional.empty());
        when(courierService.getCourierById(anyString())).thenReturn(testCourier);
        when(courierLockService.tryLock(anyString(), anyString(), anyString())).thenReturn(false);

        LogisticsException exception = assertThrows(LogisticsException.class,
                () -> deliveryService.assignTask(request));

        assertTrue(exception.getMessage().contains("配送员锁定失败"));

        verify(deliveryTaskRepository, never()).save(any(DeliveryTask.class));
        verify(courierService, never()).incrementCourierCurrent(anyString());
    }

    @Test
    @DisplayName("测试配送员状态为忙碌时的拒绝处理")
    void testCourierUnavailableWhenBusy() {
        Courier busyCourier = TestDataBuilder.buildBusyCourier(
                testCourier.getCourierId(), testCourier.getCourierStation());

        AssignTaskRequest request = TestDataBuilder.buildAssignTaskRequest(
                testLogistics.getLogisticsId(), busyCourier.getCourierId());

        when(logisticsService.getLogisticsById(anyString())).thenReturn(testLogistics);
        when(deliveryTaskRepository.findByLogisticsId(anyString())).thenReturn(Optional.empty());
        when(courierService.getCourierById(anyString())).thenReturn(busyCourier);

        LogisticsException exception = assertThrows(LogisticsException.class,
                () -> deliveryService.assignTask(request));

        assertTrue(exception.getMessage().contains("配送员不可用"));

        verify(courierLockService, never()).tryLock(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("测试配送员状态为离线时的拒绝处理")
    void testCourierUnavailableWhenOffline() {
        Courier offlineCourier = TestDataBuilder.buildOfflineCourier(
                testCourier.getCourierId(), testCourier.getCourierStation());

        AssignTaskRequest request = TestDataBuilder.buildAssignTaskRequest(
                testLogistics.getLogisticsId(), offlineCourier.getCourierId());

        when(logisticsService.getLogisticsById(anyString())).thenReturn(testLogistics);
        when(deliveryTaskRepository.findByLogisticsId(anyString())).thenReturn(Optional.empty());
        when(courierService.getCourierById(anyString())).thenReturn(offlineCourier);

        LogisticsException exception = assertThrows(LogisticsException.class,
                () -> deliveryService.assignTask(request));

        assertTrue(exception.getMessage().contains("配送员不可用"));
    }

    @Test
    @DisplayName("测试配送员容量不足时的拒绝处理")
    void testCourierCapacityExceeded() {
        Courier fullCourier = TestDataBuilder.buildTestCourier();
        fullCourier.setCourierCurrent(fullCourier.getCourierCapacity());

        AssignTaskRequest request = TestDataBuilder.buildAssignTaskRequest(
                testLogistics.getLogisticsId(), fullCourier.getCourierId());

        when(logisticsService.getLogisticsById(anyString())).thenReturn(testLogistics);
        when(deliveryTaskRepository.findByLogisticsId(anyString())).thenReturn(Optional.empty());
        when(courierService.getCourierById(anyString())).thenReturn(fullCourier);

        LogisticsException exception = assertThrows(LogisticsException.class,
                () -> deliveryService.assignTask(request));

        assertTrue(exception.getMessage().contains("配送员容量不足"));
    }

    @Test
    @DisplayName("测试不同紧急程度的任务分配")
    void testAssignmentWithDifferentUrgencyLevels() {
        testAssignmentWithUrgency(CourierLockService.URGENCY_NORMAL, "NORMAL");
        resetAllMocks();
        testAssignmentWithUrgency(CourierLockService.URGENCY_URGENT, "URGENT");
        resetAllMocks();
        testAssignmentWithUrgency(CourierLockService.URGENCY_SUPER_URGENT, "SUPER_URGENT");
    }

    private void testAssignmentWithUrgency(String urgency, String urgencyName) {
        AssignTaskRequest request = TestDataBuilder.buildAssignTaskRequest(
                testLogistics.getLogisticsId(), testCourier.getCourierId());

        when(logisticsService.getLogisticsById(anyString())).thenReturn(testLogistics);
        when(deliveryTaskRepository.findByLogisticsId(anyString())).thenReturn(Optional.empty());
        when(courierService.getCourierById(anyString())).thenReturn(testCourier);
        when(courierLockService.tryLock(anyString(), anyString(), eq(urgency))).thenReturn(true);
        when(deliveryTaskRepository.save(any(DeliveryTask.class))).thenReturn(testTask);

        AssignTaskResponse response = deliveryService.assignTaskWithUrgency(request, urgency);

        assertNotNull(response, urgencyName + "任务分配应该成功");

        verify(courierLockService, times(1)).tryLock(
                eq(testCourier.getCourierId()),
                eq(testLogistics.getLogisticsId()),
                eq(urgency));
    }

    @Test
    @DisplayName("测试配送类型配置化 - 使用配送类型的紧急程度")
    void testDeliveryTypeUrgency() {
        DeliveryType urgentType = TestDataBuilder.buildUrgentDeliveryType();
        Logistics urgentLogistics = TestDataBuilder.buildLogisticsWithDeliveryType(
                TestDataBuilder.TEST_ORDER_ID, TestDataBuilder.TEST_STATION_ID,
                DeliveryTypeService.URGENT_TYPE_CODE);

        AssignTaskRequest request = TestDataBuilder.buildAssignTaskRequest(
                urgentLogistics.getLogisticsId(), testCourier.getCourierId());

        when(logisticsService.getLogisticsById(anyString())).thenReturn(urgentLogistics);
        when(deliveryTypeService.getDeliveryType(eq(DeliveryTypeService.URGENT_TYPE_CODE))).thenReturn(urgentType);
        when(deliveryTaskRepository.findByLogisticsId(anyString())).thenReturn(Optional.empty());
        when(courierService.getCourierById(anyString())).thenReturn(testCourier);
        when(courierLockService.tryLock(anyString(), anyString(), eq(CourierLockService.URGENCY_URGENT)))
                .thenReturn(true);
        when(deliveryTaskRepository.save(any(DeliveryTask.class))).thenReturn(testTask);

        AssignTaskResponse response = deliveryService.assignTask(request);

        assertNotNull(response);

        verify(courierLockService, times(1)).tryLock(
                eq(testCourier.getCourierId()),
                eq(urgentLogistics.getLogisticsId()),
                eq(CourierLockService.URGENCY_URGENT));
    }

    @Test
    @DisplayName("测试请求指定紧急程度覆盖配送类型")
    void testRequestUrgencyOverridesDeliveryType() {
        DeliveryType normalType = TestDataBuilder.buildStandardDeliveryType();
        Logistics logistics = TestDataBuilder.buildLogisticsWithDeliveryType(
                TestDataBuilder.TEST_ORDER_ID, TestDataBuilder.TEST_STATION_ID,
                DeliveryTypeService.DEFAULT_TYPE_CODE);

        AssignTaskRequest request = TestDataBuilder.buildAssignTaskRequestWithUrgency(
                logistics.getLogisticsId(), testCourier.getCourierId(),
                CourierLockService.URGENCY_SUPER_URGENT);

        when(logisticsService.getLogisticsById(anyString())).thenReturn(logistics);
        when(deliveryTypeService.getDeliveryType(eq(DeliveryTypeService.DEFAULT_TYPE_CODE))).thenReturn(normalType);
        when(deliveryTaskRepository.findByLogisticsId(anyString())).thenReturn(Optional.empty());
        when(courierService.getCourierById(anyString())).thenReturn(testCourier);
        when(courierLockService.tryLock(anyString(), anyString(), eq(CourierLockService.URGENCY_SUPER_URGENT)))
                .thenReturn(true);
        when(deliveryTaskRepository.save(any(DeliveryTask.class))).thenReturn(testTask);

        AssignTaskResponse response = deliveryService.assignTask(request);

        assertNotNull(response);

        verify(courierLockService, times(1)).tryLock(
                eq(testCourier.getCourierId()),
                eq(logistics.getLogisticsId()),
                eq(CourierLockService.URGENCY_SUPER_URGENT));
    }

    @Test
    @DisplayName("测试配送完成后锁的释放")
    void testLockReleaseOnDeliveryComplete() {
        DeliveryTask deliveringTask = TestDataBuilder.buildTestDeliveryTask(
                testLogistics.getLogisticsId(), testCourier.getCourierId(), testLogistics.getStationId());
        deliveringTask.setTaskStatus(LogisticsConstants.TASK_STATUS_DELIVERING);

        when(deliveryTaskRepository.findById(anyString())).thenReturn(Optional.of(deliveringTask));
        when(deliveryTaskRepository.save(any(DeliveryTask.class))).thenReturn(deliveringTask);
        when(feeService.calculateFee(anyString())).thenReturn(15.0);

        deliveryService.completeDelivery(deliveringTask, "测试地址");

        verify(courierLockService, times(1)).releaseLock(
                eq(testCourier.getCourierId()),
                eq(testLogistics.getLogisticsId()));
        verify(courierService, times(1)).decrementCourierCurrent(eq(testCourier.getCourierId()));
    }

    @Test
    @DisplayName("测试配送取消后锁的释放")
    void testLockReleaseOnDeliveryCancel() {
        DeliveryTask assignedTask = TestDataBuilder.buildTestDeliveryTask(
                testLogistics.getLogisticsId(), testCourier.getCourierId(), testLogistics.getStationId());
        assignedTask.setTaskStatus(LogisticsConstants.TASK_STATUS_ASSIGNED);

        when(deliveryTaskRepository.findById(anyString())).thenReturn(Optional.of(assignedTask));
        when(deliveryTaskRepository.save(any(DeliveryTask.class))).thenReturn(assignedTask);

        deliveryService.cancelDelivery(assignedTask);

        verify(courierLockService, times(1)).releaseLock(
                eq(testCourier.getCourierId()),
                eq(testLogistics.getLogisticsId()));
    }

    @Test
    @DisplayName("测试物流已有配送任务时的拒绝处理")
    void testLogisticsAlreadyHasTask() {
        AssignTaskRequest request = TestDataBuilder.buildAssignTaskRequest(
                testLogistics.getLogisticsId(), testCourier.getCourierId());

        when(logisticsService.getLogisticsById(anyString())).thenReturn(testLogistics);
        when(deliveryTaskRepository.findByLogisticsId(anyString())).thenReturn(Optional.of(testTask));

        LogisticsException exception = assertThrows(LogisticsException.class,
                () -> deliveryService.assignTask(request));

        assertTrue(exception.getMessage().contains("该物流已有配送任务"));

        verify(courierLockService, never()).tryLock(anyString(), anyString(), anyString());
    }

    private void resetAllMocks() {
        reset(logisticsService, deliveryTaskRepository, courierService,
                courierLockService, historyService, deliveryTypeService);
        when(deliveryTypeService.getDeliveryType(anyString())).thenReturn(standardDeliveryType);
    }
}
