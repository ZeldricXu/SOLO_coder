package com.logistics.builder;

import com.logistics.constant.LogisticsConstants;
import com.logistics.dto.AssignTaskRequest;
import com.logistics.dto.CreateLogisticsRequest;
import com.logistics.dto.UpdateTaskRequest;
import com.logistics.entity.*;
import com.logistics.service.CourierLockService;
import com.logistics.service.DeliveryTypeService;
import com.logistics.util.IdGenerator;

import java.time.LocalDateTime;

public class TestDataBuilder {

    public static final String TEST_STATION_ID = "station_test_001";
    public static final String TEST_STATION_NAME = "测试配送中心";
    public static final String TEST_STATION_ADDRESS = "北京市朝阳区测试路1号";
    public static final String TEST_STATION_REGION = "朝阳区";

    public static final String TEST_COURIER_ID = "courier_test_001";
    public static final String TEST_COURIER_ID_2 = "courier_test_002";
    public static final String TEST_COURIER_NAME = "张三";
    public static final String TEST_COURIER_PHONE = "13800000001";

    public static final String TEST_ORDER_ID = "order_test_001";
    public static final String TEST_ORDER_ID_2 = "order_test_002";
    public static final String TEST_LOGISTICS_ID = "logistics_test_001";
    public static final String TEST_LOGISTICS_NUMBER = "SF20260511001";

    public static final String TEST_USER_ID = "user_test_001";
    public static final String TEST_TASK_ID = "task_test_001";

    public static final String URGENCY_NORMAL = CourierLockService.URGENCY_NORMAL;
    public static final String URGENCY_URGENT = CourierLockService.URGENCY_URGENT;
    public static final String URGENCY_SUPER_URGENT = CourierLockService.URGENCY_SUPER_URGENT;

    public static CreateLogisticsRequest buildCreateLogisticsRequest() {
        return buildCreateLogisticsRequest(TEST_ORDER_ID, TEST_STATION_ID);
    }

    public static CreateLogisticsRequest buildCreateLogisticsRequest(String orderId, String stationId) {
        CreateLogisticsRequest request = new CreateLogisticsRequest();
        request.setOrderId(orderId != null ? orderId : TEST_ORDER_ID);
        request.setStationId(stationId != null ? stationId : TEST_STATION_ID);
        return request;
    }

    public static CreateLogisticsRequest buildCreateLogisticsRequestWithType(String orderId, String stationId, String deliveryTypeCode) {
        CreateLogisticsRequest request = buildCreateLogisticsRequest(orderId, stationId);
        request.setDeliveryTypeCode(deliveryTypeCode);
        return request;
    }

    public static AssignTaskRequest buildAssignTaskRequest(String logisticsId) {
        return buildAssignTaskRequest(logisticsId, TEST_COURIER_ID);
    }

    public static AssignTaskRequest buildAssignTaskRequest(String logisticsId, String courierId) {
        AssignTaskRequest request = new AssignTaskRequest();
        request.setLogisticsId(logisticsId);
        request.setCourierId(courierId != null ? courierId : TEST_COURIER_ID);
        return request;
    }

    public static AssignTaskRequest buildAssignTaskRequestWithUrgency(String logisticsId, String courierId, String urgency) {
        AssignTaskRequest request = buildAssignTaskRequest(logisticsId, courierId);
        request.setUrgencyLevel(urgency);
        return request;
    }

    public static UpdateTaskRequest buildStartDeliveryRequest(String taskId) {
        UpdateTaskRequest request = new UpdateTaskRequest();
        request.setTaskId(taskId);
        request.setAction(LogisticsConstants.ACTION_START);
        return request;
    }

    public static UpdateTaskRequest buildUpdateTrackRequest(String taskId, String location) {
        return buildUpdateTrackRequest(taskId, location, "正在配送中");
    }

    public static UpdateTaskRequest buildUpdateTrackRequest(String taskId, String location, String detail) {
        UpdateTaskRequest request = new UpdateTaskRequest();
        request.setTaskId(taskId);
        request.setAction(LogisticsConstants.ACTION_UPDATE);
        request.setLocation(location);
        request.setDetail(detail);
        return request;
    }

    public static UpdateTaskRequest buildCompleteDeliveryRequest(String taskId, String location) {
        UpdateTaskRequest request = new UpdateTaskRequest();
        request.setTaskId(taskId);
        request.setAction(LogisticsConstants.ACTION_COMPLETE);
        request.setLocation(location);
        return request;
    }

    public static UpdateTaskRequest buildCancelDeliveryRequest(String taskId) {
        UpdateTaskRequest request = new UpdateTaskRequest();
        request.setTaskId(taskId);
        request.setAction(LogisticsConstants.ACTION_CANCEL);
        return request;
    }

    public static Station buildTestStation() {
        return buildTestStation(TEST_STATION_ID, TEST_STATION_NAME);
    }

    public static Station buildTestStation(String stationId, String name) {
        Station station = new Station();
        station.setStationId(stationId);
        station.setStationName(name != null ? name : "测试配送网点");
        station.setStationAddress(TEST_STATION_ADDRESS);
        station.setStationRegion(TEST_STATION_REGION);
        station.setStationCapacity(100);
        station.setStationCurrent(0);
        station.setStationStatus(LogisticsConstants.STATION_STATUS_ACTIVE);
        return station;
    }

    public static Courier buildTestCourier() {
        return buildTestCourier(TEST_COURIER_ID, TEST_STATION_ID, TEST_COURIER_NAME);
    }

    public static Courier buildTestCourier(String courierId, String stationId, String name) {
        Courier courier = new Courier();
        courier.setCourierId(courierId);
        courier.setCourierName(name != null ? name : "测试配送员");
        courier.setCourierPhone(TEST_COURIER_PHONE);
        courier.setCourierStation(stationId != null ? stationId : TEST_STATION_ID);
        courier.setCourierStatus(LogisticsConstants.COURIER_STATUS_AVAILABLE);
        courier.setCourierCapacity(50);
        courier.setCourierCurrent(0);
        courier.setCourierRating(4.5);
        return courier;
    }

    public static Courier buildBusyCourier(String courierId, String stationId) {
        Courier courier = buildTestCourier(courierId, stationId, "忙碌配送员");
        courier.setCourierStatus(LogisticsConstants.COURIER_STATUS_BUSY);
        courier.setCourierCurrent(10);
        return courier;
    }

    public static Courier buildOfflineCourier(String courierId, String stationId) {
        Courier courier = buildTestCourier(courierId, stationId, "离线配送员");
        courier.setCourierStatus(LogisticsConstants.COURIER_STATUS_OFFLINE);
        courier.setCourierCurrent(0);
        return courier;
    }

    public static Logistics buildTestLogistics(String orderId, String stationId) {
        Logistics logistics = new Logistics();
        logistics.setLogisticsId(TEST_LOGISTICS_ID);
        logistics.setOrderId(orderId != null ? orderId : TEST_ORDER_ID);
        logistics.setLogisticsNumber(TEST_LOGISTICS_NUMBER);
        logistics.setStationId(stationId != null ? stationId : TEST_STATION_ID);
        logistics.setDeliveryTypeCode(DeliveryTypeService.DEFAULT_TYPE_CODE);
        logistics.setLogisticsStatus(LogisticsConstants.STATUS_SHIPPING);
        logistics.setShippingTime(LocalDateTime.now());
        return logistics;
    }

    public static Logistics buildLogisticsWithStatus(String status) {
        Logistics logistics = buildTestLogistics(TEST_ORDER_ID, TEST_STATION_ID);
        logistics.setLogisticsStatus(status);
        return logistics;
    }

    public static Logistics buildLogisticsWithDeliveryType(String orderId, String stationId, String deliveryTypeCode) {
        Logistics logistics = buildTestLogistics(orderId, stationId);
        logistics.setDeliveryTypeCode(deliveryTypeCode);
        return logistics;
    }

    public static DeliveryTask buildTestDeliveryTask(String logisticsId, String courierId, String stationId) {
        DeliveryTask task = new DeliveryTask();
        task.setTaskId(TEST_TASK_ID);
        task.setLogisticsId(logisticsId);
        task.setCourierId(courierId != null ? courierId : TEST_COURIER_ID);
        task.setStationId(stationId != null ? stationId : TEST_STATION_ID);
        task.setDeliveryTypeCode(DeliveryTypeService.DEFAULT_TYPE_CODE);
        task.setUrgencyLevel(URGENCY_NORMAL);
        task.setTaskStatus(LogisticsConstants.TASK_STATUS_ASSIGNED);
        task.setAssignedAt(LocalDateTime.now());
        return task;
    }

    public static Track buildTestTrack(String logisticsId, int index) {
        Track track = new Track();
        track.setTrackId(IdGenerator.generateTrackId());
        track.setLogisticsId(logisticsId);
        track.setTrackStatus(LogisticsConstants.STATUS_DELIVERING);
        track.setTrackLocation("位置" + index + "：北京市朝阳区");
        track.setTrackTime(LocalDateTime.now().plusMinutes(index));
        track.setTrackDetail("配送员正在前往目的地");
        return track;
    }

    public static Notification buildTestNotification(String logisticsId, String status) {
        Notification notification = new Notification();
        notification.setNotifyId(IdGenerator.generateNotifyId());
        notification.setLogisticsId(logisticsId);
        notification.setNotifyType(LogisticsConstants.NOTIFY_TYPE_STATUS);
        notification.setNotifyStatus(status);
        notification.setNotifyTime(LocalDateTime.now());
        notification.setUserId(TEST_USER_ID);
        notification.setIsRead(false);
        return notification;
    }

    public static CourierLockService.CourierLock buildTestCourierLock(
            String courierId, String logisticsId, String urgency) {
        long timeoutSeconds = switch (urgency) {
            case URGENCY_SUPER_URGENT -> CourierLockService.URGENCY_SUPER_URGENT_TIMEOUT_SECONDS;
            case URGENCY_URGENT -> CourierLockService.URGENCY_URGENT_TIMEOUT_SECONDS;
            default -> CourierLockService.URGENCY_NORMAL_TIMEOUT_SECONDS;
        };
        return new CourierLockService.CourierLock(
                courierId, logisticsId, urgency, LocalDateTime.now().plusSeconds(timeoutSeconds));
    }

    public static DeliveryType buildStandardDeliveryType() {
        return buildDeliveryType(
                DeliveryTypeService.DEFAULT_TYPE_CODE,
                "标准配送",
                "普通配送，正常时效",
                URGENCY_NORMAL,
                10,
                5.0, 2.0, 0.5,
                8.0, 50.0
        );
    }

    public static DeliveryType buildUrgentDeliveryType() {
        return buildDeliveryType(
                DeliveryTypeService.URGENT_TYPE_CODE,
                "紧急配送",
                "加急配送，优先处理",
                URGENCY_URGENT,
                5,
                10.0, 3.0, 1.0,
                15.0, 100.0
        );
    }

    public static DeliveryType buildSuperUrgentDeliveryType() {
        return buildDeliveryType(
                DeliveryTypeService.SUPER_URGENT_TYPE_CODE,
                "超紧急配送",
                "立即配送，最高优先级",
                URGENCY_SUPER_URGENT,
                1,
                20.0, 5.0, 2.0,
                25.0, 200.0
        );
    }

    public static DeliveryType buildDeliveryType(
            String code, String name, String description,
            String urgency, int priority,
            double baseFee, double distanceRate, double timeRate,
            double minFee, double maxFee) {
        DeliveryType type = new DeliveryType();
        type.setTypeCode(code);
        type.setTypeName(name);
        type.setDescription(description);
        type.setUrgencyLevel(urgency);
        type.setPriority(priority);
        type.setBaseFee(baseFee);
        type.setDistanceRate(distanceRate);
        type.setTimeRate(timeRate);
        type.setMinFee(minFee);
        type.setMaxFee(maxFee);
        type.setIsActive(true);
        type.setCreatedAt(LocalDateTime.now());
        type.setUpdatedAt(LocalDateTime.now());
        return type;
    }
}
