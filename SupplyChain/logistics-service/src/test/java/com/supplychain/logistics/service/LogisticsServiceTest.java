package com.supplychain.logistics.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.supplychain.common.entity.LogisticsTracking;
import com.supplychain.common.entity.TrackingRecord;
import com.supplychain.common.enums.TrackingStatus;
import com.supplychain.common.testdata.TestDataBuilder;
import com.supplychain.logistics.mapper.LogisticsTrackingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("物流服务单元测试")
class LogisticsServiceTest {

    @Mock
    private LogisticsTrackingMapper trackingMapper;

    @InjectMocks
    private LogisticsService logisticsService;

    @BeforeEach
    void setUp() {
        logisticsService.resetAsyncMetrics();
    }

    @Nested
    @DisplayName("物流追踪异步化测试")
    class AsyncTrackingTests {

        @Test
        @DisplayName("测试物流追踪请求提交后立即返回响应")
        void testTrackingRequestReturnsImmediately() throws ExecutionException, InterruptedException {
            long startTime = System.currentTimeMillis();
            
            CompletableFuture<Map<String, Object>> future = logisticsService.submitTrackingRequest("order_async_001");
            Map<String, Object> response = future.get();
            
            long endTime = System.currentTimeMillis();
            
            assertNotNull(response);
            assertEquals("order_async_001", response.get("orderId"));
            assertEquals("submitted", response.get("status"));
            assertTrue(endTime - startTime < 100, "请求应立即返回，不应阻塞");
        }

        @Test
        @DisplayName("测试异步请求返回包含任务ID")
        void testAsyncRequestContainsTaskId() throws ExecutionException, InterruptedException {
            CompletableFuture<Map<String, Object>> future = logisticsService.submitTrackingRequest("order_task_001");
            Map<String, Object> response = future.get();
            
            assertNotNull(response.get("asyncTaskId"));
            assertTrue(response.get("asyncTaskId").toString().startsWith("task_"));
            assertNotNull(response.get("requestTime"));
        }

        @Test
        @DisplayName("测试多个异步请求的任务计数")
        void testMultipleAsyncRequests() throws ExecutionException, InterruptedException {
            assertEquals(0, logisticsService.getAsyncTaskCount());
            
            logisticsService.submitTrackingRequest("order_multi_001");
            assertEquals(1, logisticsService.getAsyncTaskCount());
            
            logisticsService.submitTrackingRequest("order_multi_002");
            assertEquals(2, logisticsService.getAsyncTaskCount());
            
            logisticsService.submitTrackingRequest("order_multi_003");
            assertEquals(3, logisticsService.getAsyncTaskCount());
        }

        @Test
        @DisplayName("测试后台Worker执行物流查询")
        void testBackgroundWorkerExecutesQuery() {
            LogisticsTracking pendingTracking = TestDataBuilder.buildPendingTracking();
            pendingTracking.setOrderId("order_worker_001");
            
            when(trackingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(pendingTracking);
            when(trackingMapper.updateById(any(LogisticsTracking.class))).thenReturn(1);

            logisticsService.executeTrackingAsync("order_worker_001");

            List<String> executions = logisticsService.getWorkerExecutions();
            assertTrue(executions.stream().anyMatch(e -> e.contains("order_worker_001")));
        }

        @Test
        @DisplayName("测试异步任务执行后更新物流状态")
        void testAsyncTaskUpdatesStatus() {
            LogisticsTracking pendingTracking = TestDataBuilder.buildPendingTracking();
            pendingTracking.setOrderId("order_update_001");
            
            when(trackingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(pendingTracking);
            when(trackingMapper.updateById(any(LogisticsTracking.class))).thenReturn(1);

            logisticsService.executeTrackingAsync("order_update_001");

            verify(trackingMapper).updateById(any(LogisticsTracking.class));
        }

        @Test
        @DisplayName("测试异步指标追踪")
        void testAsyncMetricsTracking() {
            Map<String, Object> initialMetrics = logisticsService.getAsyncMetrics();
            assertEquals(0, initialMetrics.get("asyncTaskCount"));
            assertEquals(0, initialMetrics.get("workerExecutionCount"));

            LogisticsTracking tracking = TestDataBuilder.buildPendingTracking();
            tracking.setOrderId("order_metric_001");
            when(trackingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(tracking);
            when(trackingMapper.updateById(any(LogisticsTracking.class))).thenReturn(1);

            logisticsService.submitTrackingRequest("order_metric_001");
            logisticsService.executeTrackingAsync("order_metric_001");

            Map<String, Object> metrics = logisticsService.getAsyncMetrics();
            assertEquals(2, metrics.get("asyncTaskCount"));
            assertEquals(1, metrics.get("workerExecutionCount"));
        }

        @Test
        @DisplayName("测试异步指标重置")
        void testAsyncMetricsReset() {
            LogisticsTracking tracking = TestDataBuilder.buildPendingTracking();
            tracking.setOrderId("order_reset_001");
            when(trackingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(tracking);
            when(trackingMapper.updateById(any(LogisticsTracking.class))).thenReturn(1);

            logisticsService.submitTrackingRequest("order_reset_001");
            logisticsService.executeTrackingAsync("order_reset_001");

            logisticsService.resetAsyncMetrics();

            Map<String, Object> metrics = logisticsService.getAsyncMetrics();
            assertEquals(0, metrics.get("asyncTaskCount"));
            assertEquals(0, metrics.get("workerExecutionCount"));
            assertEquals(0, metrics.get("statusNotificationCount"));
        }
    }

    @Nested
    @DisplayName("物流状态变更通知测试")
    class StatusNotificationTests {

        @Test
        @DisplayName("测试物流状态变更时发送通知")
        void testStatusChangeNotificationSent() {
            LogisticsTracking pendingTracking = TestDataBuilder.buildPendingTracking();
            pendingTracking.setOrderId("order_notif_001");
            
            when(trackingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(pendingTracking);
            when(trackingMapper.updateById(any(LogisticsTracking.class))).thenReturn(1);

            logisticsService.updateTrackingStatusWithNotification(
                    "order_notif_001", 
                    TrackingStatus.IN_TRANSIT.getCode(), 
                    "上海转运中心", 
                    "货物已发出"
            );

            List<String> notifications = logisticsService.getStatusNotifications();
            assertEquals(1, notifications.size());
            
            String notification = notifications.get(0);
            assertTrue(notification.contains("[物流通知]"));
            assertTrue(notification.contains("order_notif_001"));
            assertTrue(notification.contains("pending -> in_transit"));
            assertTrue(notification.contains("上海转运中心"));
        }

        @Test
        @DisplayName("测试多次状态变更发送多次通知")
        void testMultipleStatusChangesSendMultipleNotifications() {
            LogisticsTracking tracking = TestDataBuilder.buildPendingTracking();
            tracking.setOrderId("order_multi_notif_001");
            
            when(trackingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(tracking);
            when(trackingMapper.updateById(any(LogisticsTracking.class))).thenReturn(1);

            logisticsService.updateTrackingStatusWithNotification(
                    "order_multi_notif_001", 
                    TrackingStatus.IN_TRANSIT.getCode(), 
                    "上海转运中心", 
                    null
            );
            assertEquals(1, logisticsService.getStatusNotifications().size());

            tracking.setTrackingStatus(TrackingStatus.IN_TRANSIT.getCode());
            logisticsService.updateTrackingStatusWithNotification(
                    "order_multi_notif_001", 
                    TrackingStatus.ARRIVED.getCode(), 
                    "北京配送站", 
                    null
            );
            assertEquals(2, logisticsService.getStatusNotifications().size());
        }

        @Test
        @DisplayName("测试相同状态不发送通知")
        void testSameStatusDoesNotSendNotification() {
            LogisticsTracking tracking = TestDataBuilder.buildInTransitTracking();
            tracking.setOrderId("order_same_001");
            
            when(trackingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(tracking);
            when(trackingMapper.updateById(any(LogisticsTracking.class))).thenReturn(1);

            logisticsService.updateTrackingStatusWithNotification(
                    "order_same_001", 
                    TrackingStatus.IN_TRANSIT.getCode(), 
                    "不同位置", 
                    null
            );

            assertEquals(0, logisticsService.getStatusNotifications().size());
        }

        @Test
        @DisplayName("测试通知包含正确的位置信息")
        void testNotificationContainsLocation() {
            LogisticsTracking tracking = TestDataBuilder.buildInTransitTracking();
            tracking.setOrderId("order_loc_001");
            
            when(trackingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(tracking);
            when(trackingMapper.updateById(any(LogisticsTracking.class))).thenReturn(1);

            logisticsService.updateTrackingStatusWithNotification(
                    "order_loc_001", 
                    TrackingStatus.ARRIVED.getCode(), 
                    "北京市朝阳区配送中心", 
                    null
            );

            List<String> notifications = logisticsService.getStatusNotifications();
            assertTrue(notifications.get(0).contains("北京市朝阳区配送中心"));
        }

        @Test
        @DisplayName("测试通知列表的独立副本")
        void testNotificationListIsIndependentCopy() {
            LogisticsTracking tracking = TestDataBuilder.buildPendingTracking();
            tracking.setOrderId("order_copy_001");
            
            when(trackingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(tracking);
            when(trackingMapper.updateById(any(LogisticsTracking.class))).thenReturn(1);

            logisticsService.updateTrackingStatusWithNotification(
                    "order_copy_001", 
                    TrackingStatus.IN_TRANSIT.getCode(), 
                    "位置1", 
                    null
            );

            List<String> notifications = logisticsService.getStatusNotifications();
            notifications.add("手动添加的通知");

            assertEquals(1, logisticsService.getStatusNotifications().size());
        }

        @Test
        @DisplayName("测试清除通知")
        void testClearNotifications() {
            LogisticsTracking tracking = TestDataBuilder.buildPendingTracking();
            tracking.setOrderId("order_clear_001");
            
            when(trackingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(tracking);
            when(trackingMapper.updateById(any(LogisticsTracking.class))).thenReturn(1);

            logisticsService.updateTrackingStatusWithNotification(
                    "order_clear_001", 
                    TrackingStatus.IN_TRANSIT.getCode(), 
                    "位置1", 
                    null
            );
            assertEquals(1, logisticsService.getStatusNotifications().size());

            logisticsService.clearStatusNotifications();
            assertEquals(0, logisticsService.getStatusNotifications().size());
        }
    }

    @Nested
    @DisplayName("物流到达订单状态更新测试")
    class ArrivalOrderStatusTests {

        @Test
        @DisplayName("测试物流到达时的订单状态更新通知")
        void testLogisticsArrivalTriggersOrderStatusUpdate() {
            LogisticsTracking tracking = TestDataBuilder.buildInTransitTracking();
            tracking.setOrderId("order_arrival_001");
            
            when(trackingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(tracking);
            when(trackingMapper.updateById(any(LogisticsTracking.class))).thenReturn(1);

            logisticsService.updateTrackingStatusWithNotification(
                    "order_arrival_001", 
                    TrackingStatus.ARRIVED.getCode(), 
                    "北京配送站", 
                    null
            );

            assertTrue(logisticsService.hasArrivalNotificationSent("order_arrival_001"));
            
            List<String> notifications = logisticsService.getStatusNotifications();
            assertTrue(notifications.stream().anyMatch(n -> n.contains("[订单更新]")));
        }

        @Test
        @DisplayName("测试物流签收时的订单状态更新通知")
        void testLogisticsSignedTriggersOrderStatusUpdate() {
            LogisticsTracking tracking = TestDataBuilder.buildArrivedTracking();
            tracking.setOrderId("order_signed_001");
            
            when(trackingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(tracking);
            when(trackingMapper.updateById(any(LogisticsTracking.class))).thenReturn(1);

            logisticsService.updateTrackingStatusWithNotification(
                    "order_signed_001", 
                    TrackingStatus.SIGNED.getCode(), 
                    "客户地址", 
                    null
            );

            assertTrue(logisticsService.hasArrivalNotificationSent("order_signed_001"));
        }

        @Test
        @DisplayName("测试物流运输中不触发订单状态更新")
        void testInTransitDoesNotTriggerOrderUpdate() {
            LogisticsTracking tracking = TestDataBuilder.buildPendingTracking();
            tracking.setOrderId("order_intransit_001");
            
            when(trackingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(tracking);
            when(trackingMapper.updateById(any(LogisticsTracking.class))).thenReturn(1);

            logisticsService.updateTrackingStatusWithNotification(
                    "order_intransit_001", 
                    TrackingStatus.IN_TRANSIT.getCode(), 
                    "上海转运中心", 
                    null
            );

            assertFalse(logisticsService.hasArrivalNotificationSent("order_intransit_001"));
            
            List<String> notifications = logisticsService.getStatusNotifications();
            long orderUpdateCount = notifications.stream()
                    .filter(n -> n.contains("[订单更新]"))
                    .count();
            assertEquals(0, orderUpdateCount);
        }

        @Test
        @DisplayName("测试订单状态更新记录")
        void testOrderStatusUpdateRecord() {
            LogisticsTracking tracking = TestDataBuilder.buildInTransitTracking();
            tracking.setOrderId("order_record_001");
            
            when(trackingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(tracking);
            when(trackingMapper.updateById(any(LogisticsTracking.class))).thenReturn(1);

            logisticsService.updateTrackingStatusWithNotification(
                    "order_record_001", 
                    TrackingStatus.ARRIVED.getCode(), 
                    "北京配送站", 
                    null
            );

            Map<String, String> updates = logisticsService.getOrderStatusUpdates();
            assertTrue(updates.containsKey("order_record_001"));
            assertEquals(TrackingStatus.ARRIVED.getCode(), updates.get("order_record_001"));
        }

        @Test
        @DisplayName("测试物流追踪请求已提交")
        void testTrackingRequestSubmitted() {
            LogisticsTracking tracking = TestDataBuilder.buildPendingTracking();
            tracking.setOrderId("order_submitted_001");
            
            when(trackingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(tracking);
            when(trackingMapper.updateById(any(LogisticsTracking.class))).thenReturn(1);

            assertFalse(logisticsService.isTrackingRequestSubmitted("order_submitted_001"));
            
            logisticsService.executeTrackingAsync("order_submitted_001");
            
            assertTrue(logisticsService.isTrackingRequestSubmitted("order_submitted_001"));
        }
    }

    @Nested
    @DisplayName("物流追踪基本功能测试")
    class BasicTrackingTests {

        @Test
        @DisplayName("测试创建物流追踪")
        void testCreateTracking() {
            when(trackingMapper.insert(any(LogisticsTracking.class))).thenReturn(1);

            LogisticsTracking tracking = logisticsService.createTracking(
                    "order_create_001", 
                    "顺丰速运", 
                    "SF202605100001"
            );

            assertNotNull(tracking);
            assertNotNull(tracking.getTrackingId());
            assertEquals("order_create_001", tracking.getOrderId());
            assertEquals(TrackingStatus.PENDING.getCode(), tracking.getTrackingStatus());
            assertEquals("顺丰速运", tracking.getCarrier());
            assertEquals("SF202605100001", tracking.getTrackingNumber());
            assertNotNull(tracking.getTrackingRecords());
            assertEquals(1, tracking.getTrackingRecords().size());
            
            TrackingRecord firstRecord = tracking.getTrackingRecords().get(0);
            assertEquals(TrackingStatus.PENDING.getCode(), firstRecord.getStatus());
        }

        @Test
        @DisplayName("测试获取订单物流追踪")
        void testGetTrackingByOrder() {
            LogisticsTracking tracking = TestDataBuilder.buildInTransitTracking();
            tracking.setOrderId("order_get_001");
            
            when(trackingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(tracking);

            LogisticsTracking result = logisticsService.getTrackingByOrder("order_get_001");

            assertNotNull(result);
            assertEquals("order_get_001", result.getOrderId());
        }

        @Test
        @DisplayName("测试获取物流追踪详情")
        void testGetTracking() {
            LogisticsTracking tracking = TestDataBuilder.buildArrivedTracking();
            tracking.setTrackingId("track_get_001");
            
            when(trackingMapper.selectById("track_get_001")).thenReturn(tracking);

            LogisticsTracking result = logisticsService.getTracking("track_get_001");

            assertNotNull(result);
            assertEquals("track_get_001", result.getTrackingId());
        }

        @Test
        @DisplayName("测试物流追踪列表查询")
        void testListTrackings() {
            List<LogisticsTracking> trackings = Arrays.asList(
                    TestDataBuilder.buildPendingTracking(),
                    TestDataBuilder.buildInTransitTracking(),
                    TestDataBuilder.buildArrivedTracking()
            );
            
            when(trackingMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(trackings);

            List<LogisticsTracking> result = logisticsService.listTrackings(null, null);

            assertEquals(3, result.size());
        }

        @Test
        @DisplayName("测试按状态查询物流追踪")
        void testListTrackingsByStatus() {
            List<LogisticsTracking> trackings = Arrays.asList(
                    TestDataBuilder.buildInTransitTracking()
            );
            
            when(trackingMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(trackings);

            List<LogisticsTracking> result = logisticsService.listTrackings(
                    null, 
                    TrackingStatus.IN_TRANSIT.getCode()
            );

            assertEquals(1, result.size());
            assertEquals(TrackingStatus.IN_TRANSIT.getCode(), result.get(0).getTrackingStatus());
        }

        @Test
        @DisplayName("测试查询物流追踪信息")
        void testQueryTrackingInfo() {
            LogisticsTracking tracking = TestDataBuilder.buildInTransitTracking();
            tracking.setOrderId("order_query_001");
            
            when(trackingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(tracking);

            Map<String, Object> result = logisticsService.queryTrackingInfo("order_query_001");

            assertTrue(result.containsKey("tracking"));
            @SuppressWarnings("unchecked")
            Map<String, Object> trackingInfo = (Map<String, Object>) result.get("tracking");
            
            assertEquals(TrackingStatus.IN_TRANSIT.getCode(), trackingInfo.get("status"));
            assertNotNull(trackingInfo.get("location"));
            assertNotNull(trackingInfo.get("tracking_id"));
            assertNotNull(trackingInfo.get("carrier"));
            assertNotNull(trackingInfo.get("tracking_number"));
            assertNotNull(trackingInfo.get("records"));
        }

        @Test
        @DisplayName("测试模拟物流追踪进度")
        void testSimulateTracking() {
            LogisticsTracking tracking = TestDataBuilder.buildPendingTracking();
            tracking.setOrderId("order_sim_001");
            
            when(trackingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(tracking);
            when(trackingMapper.updateById(any(LogisticsTracking.class))).thenReturn(1);

            LogisticsTracking result = logisticsService.simulateTracking("order_sim_001");

            assertEquals(TrackingStatus.IN_TRANSIT.getCode(), result.getTrackingStatus());
        }

        @Test
        @DisplayName("测试物流状态描述")
        void testStatusDescription() {
            LogisticsTracking tracking = TestDataBuilder.buildPendingTracking();
            tracking.setOrderId("order_desc_001");
            
            when(trackingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(tracking);
            when(trackingMapper.updateById(any(LogisticsTracking.class))).thenReturn(1);

            logisticsService.updateTrackingStatus(
                    "order_desc_001", 
                    TrackingStatus.IN_TRANSIT.getCode(), 
                    "上海", 
                    null
            );

            verify(trackingMapper).updateById(any(LogisticsTracking.class));
        }
    }

    @Nested
    @DisplayName("物流追踪记录测试")
    class TrackingRecordTests {

        @Test
        @DisplayName("测试物流追踪记录累加")
        void testTrackingRecordsAccumulate() {
            LogisticsTracking tracking = TestDataBuilder.buildPendingTracking();
            tracking.setOrderId("order_accum_001");
            
            when(trackingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(tracking);
            when(trackingMapper.updateById(any(LogisticsTracking.class))).thenReturn(1);

            int initialRecords = tracking.getTrackingRecords().size();
            
            logisticsService.updateTrackingStatusWithNotification(
                    "order_accum_001", 
                    TrackingStatus.IN_TRANSIT.getCode(), 
                    "上海转运中心", 
                    null
            );

            assertTrue(tracking.getTrackingRecords().size() > initialRecords);
        }

        @Test
        @DisplayName("测试物流追踪记录包含时间戳")
        void testTrackingRecordsContainTimestamps() {
            LogisticsTracking tracking = TestDataBuilder.buildPendingTracking();
            tracking.setOrderId("order_ts_001");
            
            when(trackingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(tracking);
            when(trackingMapper.updateById(any(LogisticsTracking.class))).thenReturn(1);

            logisticsService.updateTrackingStatusWithNotification(
                    "order_ts_001", 
                    TrackingStatus.IN_TRANSIT.getCode(), 
                    "上海转运中心", 
                    null
            );

            TrackingRecord latestRecord = tracking.getTrackingRecords().get(
                    tracking.getTrackingRecords().size() - 1);
            
            assertNotNull(latestRecord.getTime());
        }

        @Test
        @DisplayName("测试物流追踪记录包含完整信息")
        void testTrackingRecordsContainCompleteInfo() {
            LogisticsTracking tracking = TestDataBuilder.buildPendingTracking();
            tracking.setOrderId("order_complete_001");
            
            when(trackingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(tracking);
            when(trackingMapper.updateById(any(LogisticsTracking.class))).thenReturn(1);

            logisticsService.updateTrackingStatusWithNotification(
                    "order_complete_001", 
                    TrackingStatus.IN_TRANSIT.getCode(), 
                    "上海转运中心", 
                    "货物已发出，正在运输中"
            );

            TrackingRecord latestRecord = tracking.getTrackingRecords().get(
                    tracking.getTrackingRecords().size() - 1);
            
            assertEquals(TrackingStatus.IN_TRANSIT.getCode(), latestRecord.getStatus());
            assertEquals("上海转运中心", latestRecord.getLocation());
            assertEquals("货物已发出，正在运输中", latestRecord.getDescription());
            assertNotNull(latestRecord.getTime());
        }
    }
}
