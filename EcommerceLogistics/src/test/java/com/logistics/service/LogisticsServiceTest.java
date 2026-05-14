package com.logistics.service;

import com.logistics.builder.TestDataBuilder;
import com.logistics.constant.LogisticsConstants;
import com.logistics.dto.CreateLogisticsRequest;
import com.logistics.dto.CreateLogisticsResponse;
import com.logistics.entity.*;
import com.logistics.exception.LogisticsException;
import com.logistics.repository.LogisticsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("物流管理模块测试")
class LogisticsServiceTest {

    @Mock
    private LogisticsRepository logisticsRepository;

    @Mock
    private StationService stationService;

    @Mock
    private CourierService courierService;

    @Mock
    private HistoryService historyService;

    @Mock
    private StatisticsService statisticsService;

    @Mock
    private DeliveryTypeService deliveryTypeService;

    @InjectMocks
    private LogisticsService logisticsService;

    private Station testStation;
    private Courier testCourier;
    private Logistics testLogistics;
    private DeliveryType standardDeliveryType;

    @BeforeEach
    void setUp() {
        testStation = TestDataBuilder.buildTestStation();
        testCourier = TestDataBuilder.buildTestCourier();
        testLogistics = TestDataBuilder.buildTestLogistics(
                TestDataBuilder.TEST_ORDER_ID, TestDataBuilder.TEST_STATION_ID);
        standardDeliveryType = TestDataBuilder.buildStandardDeliveryType();

        when(deliveryTypeService.getDeliveryType(anyString())).thenReturn(standardDeliveryType);
        when(deliveryTypeService.getDefaultDeliveryType()).thenReturn(standardDeliveryType);
    }

    @Test
    @DisplayName("测试物流创建成功")
    void testCreateLogisticsSuccess() {
        CreateLogisticsRequest request = TestDataBuilder.buildCreateLogisticsRequest();

        when(logisticsRepository.existsByOrderId(anyString())).thenReturn(false);
        when(stationService.getStationById(anyString())).thenReturn(testStation);
        when(courierService.getAvailableCouriersByStation(anyString()))
                .thenReturn(Arrays.asList(testCourier));
        when(logisticsRepository.save(any(Logistics.class))).thenReturn(testLogistics);
        when(historyService.recordHistory(any(LogisticsHistory.class))).thenReturn(null);

        CreateLogisticsResponse response = logisticsService.createLogistics(request);

        assertNotNull(response);
        assertNotNull(response.getLogisticsId());
        assertNotNull(response.getLogisticsNumber());
        assertEquals(DeliveryTypeService.DEFAULT_TYPE_CODE, response.getDeliveryTypeCode());
        assertEquals(CourierLockService.URGENCY_NORMAL, response.getUrgencyLevel());

        verify(stationService, times(1)).incrementStationCurrent(eq(TestDataBuilder.TEST_STATION_ID));
        verify(statisticsService, times(1)).incrementLogisticsCount();
        verify(historyService, times(1)).recordHistory(any(LogisticsHistory.class));
    }

    @Test
    @DisplayName("测试物流创建成功 - 指定配送类型")
    void testCreateLogisticsWithDeliveryType() {
        DeliveryType urgentType = TestDataBuilder.buildUrgentDeliveryType();
        CreateLogisticsRequest request = TestDataBuilder.buildCreateLogisticsRequestWithType(
                TestDataBuilder.TEST_ORDER_ID, TestDataBuilder.TEST_STATION_ID, 
                DeliveryTypeService.URGENT_TYPE_CODE);

        when(logisticsRepository.existsByOrderId(anyString())).thenReturn(false);
        when(stationService.getStationById(anyString())).thenReturn(testStation);
        when(courierService.getAvailableCouriersByStation(anyString()))
                .thenReturn(Arrays.asList(testCourier));
        when(deliveryTypeService.getDeliveryType(eq(DeliveryTypeService.URGENT_TYPE_CODE))).thenReturn(urgentType);
        when(logisticsRepository.save(any(Logistics.class))).thenReturn(testLogistics);
        when(historyService.recordHistory(any(LogisticsHistory.class))).thenReturn(null);

        CreateLogisticsResponse response = logisticsService.createLogistics(request);

        assertNotNull(response);
        verify(deliveryTypeService, times(1)).getDeliveryType(eq(DeliveryTypeService.URGENT_TYPE_CODE));
    }

    @Test
    @DisplayName("测试物流状态流转：已发货 -> 配送中")
    void testLogisticsStatusTransitionToDelivering() {
        when(logisticsRepository.findById(anyString())).thenReturn(Optional.of(testLogistics));
        when(logisticsRepository.save(any(Logistics.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Logistics result = logisticsService.updateLogisticsStatus(
                testLogistics.getLogisticsId(), LogisticsConstants.STATUS_DELIVERING);

        assertNotNull(result);
        assertEquals(LogisticsConstants.STATUS_DELIVERING, result.getLogisticsStatus());
        assertNull(result.getDeliveryTime());
    }

    @Test
    @DisplayName("测试物流状态流转：已发货 -> 配送中 -> 已送达")
    void testCompleteLogisticsStatusTransition() {
        Logistics shippingLogistics = TestDataBuilder.buildLogisticsWithStatus(LogisticsConstants.STATUS_SHIPPING);
        shippingLogistics.setShippingTime(LocalDateTime.now());

        when(logisticsRepository.findById(anyString()))
                .thenReturn(Optional.of(shippingLogistics));
        when(logisticsRepository.save(any(Logistics.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Logistics delivering = logisticsService.updateLogisticsStatus(
                shippingLogistics.getLogisticsId(), LogisticsConstants.STATUS_DELIVERING);
        assertEquals(LogisticsConstants.STATUS_DELIVERING, delivering.getLogisticsStatus());

        Logistics delivered = logisticsService.updateLogisticsStatus(
                shippingLogistics.getLogisticsId(), LogisticsConstants.STATUS_DELIVERED);
        assertEquals(LogisticsConstants.STATUS_DELIVERED, delivered.getLogisticsStatus());
        assertNotNull(delivered.getDeliveryTime());
    }

    @Test
    @DisplayName("测试物流状态流转时设置送达时间")
    void testDeliveryTimeSetOnDelivered() {
        testLogistics.setShippingTime(LocalDateTime.now().minusHours(2));

        when(logisticsRepository.findById(anyString())).thenReturn(Optional.of(testLogistics));
        when(logisticsRepository.save(any(Logistics.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Logistics result = logisticsService.updateLogisticsStatus(
                testLogistics.getLogisticsId(), LogisticsConstants.STATUS_DELIVERED);

        assertNotNull(result.getDeliveryTime());
        assertTrue(result.getDeliveryTime().isAfter(result.getShippingTime()));
    }

    @Test
    @DisplayName("测试按ID查询物流")
    void testGetLogisticsById() {
        when(logisticsRepository.findById(anyString())).thenReturn(Optional.of(testLogistics));

        Logistics result = logisticsService.getLogisticsById(testLogistics.getLogisticsId());

        assertNotNull(result);
        assertEquals(testLogistics.getLogisticsId(), result.getLogisticsId());
        assertEquals(testLogistics.getOrderId(), result.getOrderId());
        assertEquals(DeliveryTypeService.DEFAULT_TYPE_CODE, result.getDeliveryTypeCode());
    }

    @Test
    @DisplayName("测试按物流编号查询物流")
    void testGetLogisticsByNumber() {
        String logisticsNumber = "SF20260511123456";
        testLogistics.setLogisticsNumber(logisticsNumber);

        when(logisticsRepository.findByLogisticsNumber(eq(logisticsNumber)))
                .thenReturn(Optional.of(testLogistics));

        Logistics result = logisticsService.getLogisticsByNumber(logisticsNumber);

        assertNotNull(result);
        assertEquals(logisticsNumber, result.getLogisticsNumber());
    }

    @Test
    @DisplayName("测试订单已存在物流时拒绝创建")
    void testCreateLogisticsWithExistingOrder() {
        CreateLogisticsRequest request = TestDataBuilder.buildCreateLogisticsRequest();

        when(logisticsRepository.existsByOrderId(anyString())).thenReturn(true);

        LogisticsException exception = assertThrows(LogisticsException.class,
                () -> logisticsService.createLogistics(request));

        assertTrue(exception.getMessage().contains("该订单已存在物流记录"));

        verify(logisticsRepository, never()).save(any(Logistics.class));
    }

    @Test
    @DisplayName("测试网点不可用时拒绝创建")
    void testCreateLogisticsWithInactiveStation() {
        Station inactiveStation = TestDataBuilder.buildTestStation();
        inactiveStation.setStationStatus(LogisticsConstants.STATION_STATUS_CLOSED);

        CreateLogisticsRequest request = TestDataBuilder.buildCreateLogisticsRequest();

        when(logisticsRepository.existsByOrderId(anyString())).thenReturn(false);
        when(stationService.getStationById(anyString())).thenReturn(inactiveStation);

        LogisticsException exception = assertThrows(LogisticsException.class,
                () -> logisticsService.createLogistics(request));

        assertTrue(exception.getMessage().contains("网点不可用"));
    }

    @Test
    @DisplayName("测试网点容量不足时拒绝创建")
    void testCreateLogisticsWithFullStation() {
        Station fullStation = TestDataBuilder.buildTestStation();
        fullStation.setStationCurrent(fullStation.getStationCapacity());

        CreateLogisticsRequest request = TestDataBuilder.buildCreateLogisticsRequest();

        when(logisticsRepository.existsByOrderId(anyString())).thenReturn(false);
        when(stationService.getStationById(anyString())).thenReturn(fullStation);

        LogisticsException exception = assertThrows(LogisticsException.class,
                () -> logisticsService.createLogistics(request));

        assertTrue(exception.getMessage().contains("网点容量不足"));
    }

    @Test
    @DisplayName("测试网点无可用配送员时拒绝创建")
    void testCreateLogisticsWithNoAvailableCouriers() {
        CreateLogisticsRequest request = TestDataBuilder.buildCreateLogisticsRequest();

        when(logisticsRepository.existsByOrderId(anyString())).thenReturn(false);
        when(stationService.getStationById(anyString())).thenReturn(testStation);
        when(courierService.getAvailableCouriersByStation(anyString()))
                .thenReturn(Arrays.asList());

        LogisticsException exception = assertThrows(LogisticsException.class,
                () -> logisticsService.createLogistics(request));

        assertTrue(exception.getMessage().contains("该网点暂无可用配送员"));
    }

    @Test
    @DisplayName("测试更新物流配送员和费用")
    void testUpdateLogisticsCourierAndFee() {
        String newCourierId = "courier_new_001";
        Double newFee = 25.0;

        when(logisticsRepository.findById(anyString())).thenReturn(Optional.of(testLogistics));
        when(logisticsRepository.save(any(Logistics.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Logistics result = logisticsService.updateLogistics(
                testLogistics.getLogisticsId(), newCourierId, newFee);

        assertEquals(newCourierId, result.getCourierId());
        assertEquals(newFee, result.getLogisticsFee());
    }

    @Test
    @DisplayName("测试查询所有物流")
    void testGetAllLogistics() {
        Logistics logistics1 = TestDataBuilder.buildTestLogistics("order_1", "station_1");
        Logistics logistics2 = TestDataBuilder.buildTestLogistics("order_2", "station_2");
        List<Logistics> logisticsList = Arrays.asList(logistics1, logistics2);

        when(logisticsRepository.findAll()).thenReturn(logisticsList);

        List<Logistics> result = logisticsService.getAllLogistics();

        assertNotNull(result);
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("测试物流不存在时抛出异常")
    void testGetNonExistentLogistics() {
        when(logisticsRepository.findById(anyString())).thenReturn(Optional.empty());

        LogisticsException exception = assertThrows(LogisticsException.class,
                () -> logisticsService.getLogisticsById("non_existent"));

        assertTrue(exception.getMessage().contains("物流记录不存在"));
    }

    @Test
    @DisplayName("测试订单ID为空时的校验")
    void testValidateOrderWithEmptyOrderId() {
        CreateLogisticsRequest request = new CreateLogisticsRequest();
        request.setOrderId("");
        request.setStationId(TestDataBuilder.TEST_STATION_ID);

        LogisticsException exception = assertThrows(LogisticsException.class,
                () -> logisticsService.createLogistics(request));

        assertTrue(exception.getMessage().contains("订单ID不能为空"));
    }
}
