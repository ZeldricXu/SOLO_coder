package com.supplychain.common.testdata;

import com.supplychain.common.dto.*;
import com.supplychain.common.entity.*;
import com.supplychain.common.enums.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

public class TestDataBuilder {

    public static Supplier buildQualifiedSupplier() {
        return Supplier.builder()
            .supplierId("supplier_qual_001")
            .supplierName("优秀供应商科技有限公司")
            .supplierType("material")
            .supplierContact("张经理 13800138000")
            .supplierAddress("北京市朝阳区供应链产业园A座101")
            .supplierStatus(SupplierStatus.QUALIFIED.getCode())
            .supplierRating(4.5)
            .registeredAt(LocalDateTime.of(2025, 1, 15, 10, 0, 0))
            .updatedAt(LocalDateTime.of(2026, 4, 20, 14, 30, 0))
            .build();
    }

    public static Supplier buildPendingSupplier() {
        return Supplier.builder()
            .supplierId("supplier_pend_001")
            .supplierName("待审核供应商有限公司")
            .supplierType("material")
            .supplierContact("李经理 13900139000")
            .supplierAddress("上海市浦东新区供应商大厦B座202")
            .supplierStatus(SupplierStatus.PENDING.getCode())
            .supplierRating(0.0)
            .registeredAt(LocalDateTime.of(2026, 5, 1, 9, 0, 0))
            .updatedAt(LocalDateTime.of(2026, 5, 1, 9, 0, 0))
            .build();
    }

    public static Supplier buildDisqualifiedSupplier() {
        return Supplier.builder()
            .supplierId("supplier_disq_001")
            .supplierName("不合格供应商有限公司")
            .supplierType("service")
            .supplierContact("王经理 13700137000")
            .supplierAddress("广州市天河区商业中心C座303")
            .supplierStatus(SupplierStatus.DISQUALIFIED.getCode())
            .supplierRating(2.3)
            .registeredAt(LocalDateTime.of(2025, 6, 10, 11, 0, 0))
            .updatedAt(LocalDateTime.of(2026, 3, 15, 16, 0, 0))
            .build();
    }

    public static Supplier buildSuspendedSupplier() {
        return Supplier.builder()
            .supplierId("supplier_suspended_001")
            .supplierName("已停用供应商有限公司")
            .supplierType("material")
            .supplierContact("刘经理 13600136000")
            .supplierAddress("深圳市南山区科技园D座404")
            .supplierStatus(SupplierStatus.SUSPENDED.getCode())
            .supplierRating(3.0)
            .registeredAt(LocalDateTime.of(2025, 3, 1, 9, 0, 0))
            .updatedAt(LocalDateTime.of(2026, 2, 28, 17, 0, 0))
            .build();
    }

    public static Supplier buildHighRatingSupplier() {
        return Supplier.builder()
            .supplierId("supplier_high_001")
            .supplierName("高评级供应商有限公司")
            .supplierType("material")
            .supplierContact("周经理 13500135000")
            .supplierAddress("杭州市西湖区创业园E座505")
            .supplierStatus(SupplierStatus.QUALIFIED.getCode())
            .supplierRating(4.9)
            .registeredAt(LocalDateTime.of(2024, 12, 1, 8, 30, 0))
            .updatedAt(LocalDateTime.of(2026, 5, 1, 10, 0, 0))
            .build();
    }

    public static List<Supplier> buildSupplierList(int count) {
        List<Supplier> suppliers = new ArrayList<>();
        String[] types = {"material", "service", "equipment", "logistics"};
        String[] statuses = {SupplierStatus.QUALIFIED.getCode(), SupplierStatus.PENDING.getCode()};
        
        for (int i = 1; i <= count; i++) {
            suppliers.add(Supplier.builder()
                .supplierId("supplier_list_" + String.format("%03d", i))
                .supplierName("测试供应商" + i + "号有限公司")
                .supplierType(types[i % types.length])
                .supplierContact("联系人" + i + " 13800" + String.format("%06d", i))
                .supplierAddress("测试地址" + i + "号")
                .supplierStatus(statuses[i % statuses.length])
                .supplierRating(3.0 + Math.random() * 2.0)
                .registeredAt(LocalDateTime.now().minusDays(i * 30L))
                .updatedAt(LocalDateTime.now().minusDays(i))
                .build());
        }
        return suppliers;
    }

    public static SupplierEvaluation buildEvaluation(String supplierId) {
        return SupplierEvaluation.builder()
            .evaluationId("eval_001")
            .supplierId(supplierId)
            .qualityScore(4.5)
            .deliveryScore(4.2)
            .priceScore(4.0)
            .serviceScore(4.3)
            .totalScore(4.25)
            .evaluationResult("good")
            .evaluator("评估员张三")
            .evaluationTime(LocalDateTime.now())
            .build();
    }

    public static SupplierEvaluation buildExcellentEvaluation(String supplierId) {
        return SupplierEvaluation.builder()
            .evaluationId("eval_excellent_001")
            .supplierId(supplierId)
            .qualityScore(4.9)
            .deliveryScore(4.8)
            .priceScore(4.7)
            .serviceScore(4.9)
            .totalScore(4.825)
            .evaluationResult("excellent")
            .evaluator("高级评估员李四")
            .evaluationTime(LocalDateTime.now())
            .build();
    }

    public static SupplierEvaluation buildFailedEvaluation(String supplierId) {
        return SupplierEvaluation.builder()
            .evaluationId("eval_failed_001")
            .supplierId(supplierId)
            .qualityScore(2.5)
            .deliveryScore(2.0)
            .priceScore(3.0)
            .serviceScore(2.2)
            .totalScore(2.425)
            .evaluationResult("failed")
            .evaluator("评估员王五")
            .evaluationTime(LocalDateTime.now())
            .build();
    }

    public static PurchaseOrder buildPendingApprovalOrder() {
        return PurchaseOrder.builder()
            .orderId("order_pending_001")
            .supplierId("supplier_qual_001")
            .orderType("purchase")
            .orderItems(buildOrderItems(3))
            .orderAmount(new BigDecimal("15500.00"))
            .orderStatus(OrderStatus.PENDING_APPROVAL.getCode())
            .createdAt(LocalDateTime.now().minusHours(2))
            .build();
    }

    public static PurchaseOrder buildConfirmedOrder() {
        return PurchaseOrder.builder()
            .orderId("order_confirmed_001")
            .supplierId("supplier_qual_001")
            .orderType("purchase")
            .orderItems(buildOrderItems(2))
            .orderAmount(new BigDecimal("8500.00"))
            .orderStatus(OrderStatus.CONFIRMED.getCode())
            .approver("审批人赵六")
            .createdAt(LocalDateTime.now().minusDays(1))
            .confirmedAt(LocalDateTime.now().minusHours(20))
            .build();
    }

    public static PurchaseOrder buildUrgentOrder() {
        return PurchaseOrder.builder()
            .orderId("order_urgent_001")
            .supplierId("supplier_qual_001")
            .orderType("urgent_purchase")
            .orderItems(buildOrderItems(1))
            .orderAmount(new BigDecimal("25000.00"))
            .orderStatus(OrderStatus.PENDING_APPROVAL.getCode())
            .createdAt(LocalDateTime.now().minusMinutes(30))
            .build();
    }

    public static PurchaseOrder buildReceivedOrder() {
        return PurchaseOrder.builder()
            .orderId("order_received_001")
            .supplierId("supplier_qual_001")
            .orderType("purchase")
            .orderItems(buildOrderItems(4))
            .orderAmount(new BigDecimal("32000.00"))
            .orderStatus(OrderStatus.RECEIVED.getCode())
            .approver("审批人赵六")
            .createdAt(LocalDateTime.now().minusDays(5))
            .confirmedAt(LocalDateTime.now().minusDays(4))
            .receivedAt(LocalDateTime.now().minusHours(2))
            .build();
    }

    public static List<OrderItem> buildOrderItems(int count) {
        List<OrderItem> items = new ArrayList<>();
        String[] names = {"原材料A", "零部件B", "配件C", "耗材D", "设备E"};
        for (int i = 0; i < count; i++) {
            items.add(OrderItem.builder()
                .itemId("item_" + String.format("%03d", i + 1))
                .itemName(names[i % names.length])
                .quantity(100 + i * 50)
                .price(new BigDecimal(50 + i * 30))
                .build());
        }
        return items;
    }

    public static OrderCreateRequest buildOrderCreateRequest(String supplierId) {
        return OrderCreateRequest.builder()
            .supplierId(supplierId)
            .orderItems(buildOrderItemRequests(3))
            .build();
    }

    public static List<OrderItemRequest> buildOrderItemRequests(int count) {
        List<OrderItemRequest> items = new ArrayList<>();
        String[] names = {"原材料A", "零部件B", "配件C", "耗材D", "设备E"};
        for (int i = 0; i < count; i++) {
            items.add(OrderItemRequest.builder()
                .itemId("req_item_" + String.format("%03d", i + 1))
                .itemName(names[i % names.length])
                .quantity(100 + i * 50)
                .price(new BigDecimal(50 + i * 30))
                .build());
        }
        return items;
    }

    public static Inventory buildNormalInventory() {
        return Inventory.builder()
            .inventoryId("inv_normal_001")
            .itemId("item_001")
            .itemName("常用原材料")
            .supplierId("supplier_qual_001")
            .quantity(200)
            .unitPrice(new BigDecimal("50.00"))
            .warningThreshold(100)
            .lastSyncTime(LocalDateTime.now().minusMinutes(5))
            .updatedAt(LocalDateTime.now().minusMinutes(5))
            .build();
    }

    public static Inventory buildLowStockInventory() {
        return Inventory.builder()
            .inventoryId("inv_low_001")
            .itemId("item_002")
            .itemName("紧俏原材料")
            .supplierId("supplier_qual_001")
            .quantity(30)
            .unitPrice(new BigDecimal("100.00"))
            .warningThreshold(100)
            .lastSyncTime(LocalDateTime.now().minusHours(1))
            .updatedAt(LocalDateTime.now().minusHours(1))
            .build();
    }

    public static Inventory buildCriticalLowInventory() {
        return Inventory.builder()
            .inventoryId("inv_critical_001")
            .itemId("item_003")
            .itemName("关键零部件")
            .supplierId("supplier_qual_001")
            .quantity(15)
            .unitPrice(new BigDecimal("200.00"))
            .warningThreshold(100)
            .lastSyncTime(LocalDateTime.now().minusMinutes(30))
            .updatedAt(LocalDateTime.now().minusMinutes(30))
            .build();
    }

    public static Inventory buildOverStockInventory() {
        return Inventory.builder()
            .inventoryId("inv_over_001")
            .itemId("item_004")
            .itemName("滞销商品")
            .supplierId("supplier_qual_001")
            .quantity(5000)
            .unitPrice(new BigDecimal("20.00"))
            .warningThreshold(1000)
            .lastSyncTime(LocalDateTime.now().minusDays(7))
            .updatedAt(LocalDateTime.now().minusDays(7))
            .build();
    }

    public static InventorySyncRequest buildInventorySyncRequest(String supplierId) {
        Map<String, Map<String, Object>> syncData = new HashMap<>();
        
        Map<String, Object> item1 = new HashMap<>();
        item1.put("quantity", 150);
        item1.put("item_name", "原材料A");
        item1.put("price", 50.00);
        item1.put("warning_threshold", 100);
        syncData.put("item_001", item1);
        
        Map<String, Object> item2 = new HashMap<>();
        item2.put("quantity", 250);
        item2.put("item_name", "零部件B");
        item2.put("price", 30.00);
        item2.put("warning_threshold", 200);
        syncData.put("item_002", item2);
        
        return InventorySyncRequest.builder()
            .supplierId(supplierId)
            .syncType("inventory")
            .syncData(syncData)
            .build();
    }

    public static InventorySyncRequest buildLowStockSyncRequest(String supplierId) {
        Map<String, Map<String, Object>> syncData = new HashMap<>();
        
        Map<String, Object> item1 = new HashMap<>();
        item1.put("quantity", 25);
        item1.put("item_name", "紧俏原材料");
        item1.put("price", 100.00);
        item1.put("warning_threshold", 100);
        syncData.put("item_low_001", item1);
        
        Map<String, Object> item2 = new HashMap<>();
        item2.put("quantity", 10);
        item2.put("item_name", "关键零部件");
        item2.put("price", 200.00);
        item2.put("warning_threshold", 100);
        syncData.put("item_low_002", item2);
        
        return InventorySyncRequest.builder()
            .supplierId(supplierId)
            .syncType("inventory")
            .syncData(syncData)
            .build();
    }

    public static InventorySync buildInventorySyncRecord() {
        return InventorySync.builder()
            .syncId("sync_001")
            .supplierId("supplier_qual_001")
            .syncType("inventory")
            .syncData(new HashMap<>())
            .syncTime(LocalDateTime.now())
            .build();
    }

    public static InventoryWarning buildLowStockWarning() {
        return InventoryWarning.builder()
            .warningId("warn_low_001")
            .itemId("item_001")
            .warningType(WarningType.LOW_STOCK.getCode())
            .warningLevel(WarningLevel.MEDIUM.getCode())
            .currentQuantity(50)
            .warningThreshold(100)
            .triggeredAt(LocalDateTime.now().minusHours(1))
            .status("active")
            .build();
    }

    public static InventoryWarning buildCriticalWarning() {
        return InventoryWarning.builder()
            .warningId("warn_critical_001")
            .itemId("item_002")
            .warningType(WarningType.LOW_STOCK.getCode())
            .warningLevel(WarningLevel.CRITICAL.getCode())
            .currentQuantity(10)
            .warningThreshold(100)
            .triggeredAt(LocalDateTime.now().minusMinutes(15))
            .status("active")
            .build();
    }

    public static InventoryWarning buildHandledWarning() {
        return InventoryWarning.builder()
            .warningId("warn_handled_001")
            .itemId("item_003")
            .warningType(WarningType.LOW_STOCK.getCode())
            .warningLevel(WarningLevel.MEDIUM.getCode())
            .currentQuantity(30)
            .warningThreshold(100)
            .triggeredAt(LocalDateTime.now().minusDays(2))
            .status("handled")
            .handler("仓库管理员小陈")
            .handledAt(LocalDateTime.now().minusDays(1))
            .build();
    }

    public static LogisticsTracking buildPendingTracking() {
        List<TrackingRecord> records = new ArrayList<>();
        records.add(TrackingRecord.builder()
            .status(TrackingStatus.PENDING.getCode())
            .location("供应商仓库")
            .description("订单已确认，等待发货")
            .time(LocalDateTime.now().minusHours(2))
            .build());
        
        return LogisticsTracking.builder()
            .trackingId("track_pending_001")
            .orderId("order_confirmed_001")
            .trackingStatus(TrackingStatus.PENDING.getCode())
            .trackingLocation("供应商仓库")
            .trackingTime(LocalDateTime.now().minusHours(2))
            .carrier("顺丰速运")
            .trackingNumber("SF202605100001")
            .trackingRecords(records)
            .build();
    }

    public static LogisticsTracking buildInTransitTracking() {
        List<TrackingRecord> records = new ArrayList<>();
        records.add(TrackingRecord.builder()
            .status(TrackingStatus.PENDING.getCode())
            .location("供应商仓库")
            .description("订单已确认，等待发货")
            .time(LocalDateTime.now().minusDays(2))
            .build());
        records.add(TrackingRecord.builder()
            .status(TrackingStatus.IN_TRANSIT.getCode())
            .location("上海转运中心")
            .description("货物已发出，运输中")
            .time(LocalDateTime.now().minusDays(1))
            .build());
        
        return LogisticsTracking.builder()
            .trackingId("track_transit_001")
            .orderId("order_confirmed_002")
            .trackingStatus(TrackingStatus.IN_TRANSIT.getCode())
            .trackingLocation("上海转运中心")
            .trackingTime(LocalDateTime.now().minusHours(6))
            .carrier("顺丰速运")
            .trackingNumber("SF202605100002")
            .trackingRecords(records)
            .build();
    }

    public static LogisticsTracking buildArrivedTracking() {
        List<TrackingRecord> records = new ArrayList<>();
        records.add(TrackingRecord.builder()
            .status(TrackingStatus.PENDING.getCode())
            .location("供应商仓库")
            .description("订单已确认，等待发货")
            .time(LocalDateTime.now().minusDays(3))
            .build());
        records.add(TrackingRecord.builder()
            .status(TrackingStatus.IN_TRANSIT.getCode())
            .location("上海转运中心")
            .description("货物已发出，运输中")
            .time(LocalDateTime.now().minusDays(2))
            .build());
        records.add(TrackingRecord.builder()
            .status(TrackingStatus.ARRIVED.getCode())
            .location("北京配送站")
            .description("货物已到达目的地")
            .time(LocalDateTime.now().minusHours(2))
            .build());
        
        return LogisticsTracking.builder()
            .trackingId("track_arrived_001")
            .orderId("order_confirmed_003")
            .trackingStatus(TrackingStatus.ARRIVED.getCode())
            .trackingLocation("北京配送站")
            .trackingTime(LocalDateTime.now().minusHours(2))
            .carrier("顺丰速运")
            .trackingNumber("SF202605100003")
            .trackingRecords(records)
            .build();
    }

    public static LogisticsTracking buildSignedTracking() {
        List<TrackingRecord> records = new ArrayList<>();
        records.add(TrackingRecord.builder()
            .status(TrackingStatus.PENDING.getCode())
            .location("供应商仓库")
            .description("订单已确认，等待发货")
            .time(LocalDateTime.now().minusDays(4))
            .build());
        records.add(TrackingRecord.builder()
            .status(TrackingStatus.IN_TRANSIT.getCode())
            .location("上海转运中心")
            .description("货物已发出，运输中")
            .time(LocalDateTime.now().minusDays(3))
            .build());
        records.add(TrackingRecord.builder()
            .status(TrackingStatus.ARRIVED.getCode())
            .location("北京配送站")
            .description("货物已到达目的地")
            .time(LocalDateTime.now().minusDays(2))
            .build());
        records.add(TrackingRecord.builder()
            .status(TrackingStatus.SIGNED.getCode())
            .location("客户地址")
            .description("货物已签收，签收人：张先生")
            .time(LocalDateTime.now().minusHours(5))
            .build());
        
        return LogisticsTracking.builder()
            .trackingId("track_signed_001")
            .orderId("order_received_001")
            .trackingStatus(TrackingStatus.SIGNED.getCode())
            .trackingLocation("客户地址")
            .trackingTime(LocalDateTime.now().minusHours(5))
            .carrier("顺丰速运")
            .trackingNumber("SF202605100004")
            .trackingRecords(records)
            .build();
    }

    public static Contract buildDraftContract() {
        return Contract.builder()
            .contractId("contract_draft_001")
            .contractNo("PO-20260510-0001")
            .supplierId("supplier_qual_001")
            .orderIds(Arrays.asList("order_confirmed_001"))
            .contractContent("采购合同 - 原材料采购")
            .contractAmount(new BigDecimal("15500.00"))
            .contractStatus(ContractStatus.DRAFT.getCode())
            .startDate(LocalDateTime.now())
            .endDate(LocalDateTime.now().plusMonths(3))
            .createdAt(LocalDateTime.now())
            .build();
    }

    public static Contract buildSignedContract() {
        return Contract.builder()
            .contractId("contract_signed_001")
            .contractNo("PO-20260509-0002")
            .supplierId("supplier_qual_001")
            .orderIds(Arrays.asList("order_confirmed_002", "order_confirmed_003"))
            .contractContent("季度采购合同")
            .contractAmount(new BigDecimal("50000.00"))
            .contractStatus(ContractStatus.SIGNED.getCode())
            .startDate(LocalDateTime.now().minusDays(5))
            .endDate(LocalDateTime.now().plusMonths(3))
            .createdAt(LocalDateTime.now().minusDays(5))
            .signedAt(LocalDateTime.now().minusDays(3))
            .build();
    }

    public static SupplierMessage buildUnreadMessage() {
        return SupplierMessage.builder()
            .messageId("msg_unread_001")
            .supplierId("supplier_qual_001")
            .sender("供应商张经理")
            .receiver("采购专员小李")
            .messageType("order_inquiry")
            .messageContent("您好，请问订单order_001的付款流程进展如何？")
            .relatedOrderId("order_confirmed_001")
            .status("sent")
            .sentAt(LocalDateTime.now().minusHours(1))
            .build();
    }

    public static SupplierMessage buildReadMessage() {
        return SupplierMessage.builder()
            .messageId("msg_read_001")
            .supplierId("supplier_qual_001")
            .sender("采购专员小李")
            .receiver("供应商张经理")
            .messageType("order_notification")
            .messageContent("订单已确认，请尽快安排发货。")
            .relatedOrderId("order_confirmed_002")
            .status("read")
            .sentAt(LocalDateTime.now().minusDays(1))
            .readAt(LocalDateTime.now().minusHours(20))
            .build();
    }

    public static HistoryRecord buildPurchaseHistoryRecord() {
        return HistoryRecord.builder()
            .recordId("hist_pur_001")
            .recordType("purchase")
            .relatedId("order_confirmed_001")
            .action("订单审批通过")
            .operator("审批人赵六")
            .detail("订单金额15500.00元，供应商：优秀供应商科技有限公司")
            .createdAt(LocalDateTime.now().minusHours(2))
            .build();
    }

    public static HistoryRecord buildInventoryHistoryRecord() {
        return HistoryRecord.builder()
            .recordId("hist_inv_001")
            .recordType("inventory")
            .relatedId("item_001")
            .action("库存同步更新")
            .operator("系统自动")
            .detail("库存数量从150更新为200，同步时间：" + LocalDateTime.now())
            .createdAt(LocalDateTime.now().minusMinutes(30))
            .build();
    }

    public static HistoryRecord buildLogisticsHistoryRecord() {
        return HistoryRecord.builder()
            .recordId("hist_log_001")
            .recordType("logistics")
            .relatedId("order_confirmed_001")
            .action("物流状态更新")
            .operator("物流系统")
            .detail("物流状态从pending更新为in_transit")
            .createdAt(LocalDateTime.now().minusHours(6))
            .build();
    }

    public static PurchaseStatistics buildCurrentMonthStats() {
        return PurchaseStatistics.builder()
            .statId("stat_202605")
            .statMonth("2026-05")
            .orderCount(156)
            .totalAmount(new BigDecimal("2580000.00"))
            .supplierCount(28)
            .build();
    }

    public static Map<String, Integer> buildApprovalTimeoutConfig() {
        Map<String, Integer> config = new HashMap<>();
        config.put("urgent", 30);
        config.put("normal", 120);
        config.put("low", 480);
        return config;
    }

    public static Map<String, Integer> buildWarningThresholdConfig() {
        Map<String, Integer> config = new HashMap<>();
        config.put("critical", 25);
        config.put("high", 50);
        config.put("medium", 80);
        return config;
    }

    public static com.supplychain.common.entity.LogisticsTask buildLogisticsTask() {
        return com.supplychain.common.entity.LogisticsTask.builder()
                .taskId("task_001")
                .orderId("order_confirmed_001")
                .carrier("顺丰速运")
                .trackingNumber("SF202605100001")
                .status(com.supplychain.common.entity.LogisticsTask.TaskStatus.PENDING)
                .retryCount(0)
                .maxRetries(3)
                .createdAt(LocalDateTime.now())
                .priority("normal")
                .metadata(Map.of("autoTrack", true, "notificationEnabled", true))
                .build();
    }

    public static com.supplychain.common.entity.LogisticsTask buildProcessingLogisticsTask() {
        com.supplychain.common.entity.LogisticsTask task = buildLogisticsTask();
        task.setTaskId("task_processing_001");
        task.setStatus(com.supplychain.common.entity.LogisticsTask.TaskStatus.PROCESSING);
        task.setExecutedAt(LocalDateTime.now().minusSeconds(30));
        return task;
    }

    public static com.supplychain.common.entity.LogisticsTask buildRetryLogisticsTask() {
        com.supplychain.common.entity.LogisticsTask task = buildLogisticsTask();
        task.setTaskId("task_retry_001");
        task.setStatus(com.supplychain.common.entity.LogisticsTask.TaskStatus.RETRYING);
        task.setRetryCount(2);
        task.setNextRetryAt(LocalDateTime.now().plusMinutes(2));
        task.setErrorMessage("网络超时，等待重试");
        return task;
    }

    public static com.supplychain.common.entity.LogisticsTask buildCompletedLogisticsTask() {
        com.supplychain.common.entity.LogisticsTask task = buildLogisticsTask();
        task.setTaskId("task_completed_001");
        task.setStatus(com.supplychain.common.entity.LogisticsTask.TaskStatus.COMPLETED);
        task.setExecutedAt(LocalDateTime.now().minusHours(1));
        return task;
    }

    public static com.supplychain.common.entity.LogisticsTask buildFailedLogisticsTask() {
        com.supplychain.common.entity.LogisticsTask task = buildLogisticsTask();
        task.setTaskId("task_failed_001");
        task.setStatus(com.supplychain.common.entity.LogisticsTask.TaskStatus.FAILED);
        task.setRetryCount(3);
        task.setErrorMessage("物流查询接口异常，已达到最大重试次数");
        return task;
    }

    public static com.supplychain.common.config.ApprovalTimeoutConfig buildUrgentTimeoutConfig() {
        return com.supplychain.common.config.ApprovalTimeoutConfig.builder()
                .configId("cfg_timeout_custom_urgent")
                .orderType("urgent_purchase")
                .timeoutMinutes(15)
                .description("紧急采购 - 自定义15分钟超时快速提醒")
                .notificationIntervalMinutes(5)
                .maxNotifications(8)
                .enabled(true)
                .createdAt(LocalDateTime.now().minusDays(7))
                .updatedAt(LocalDateTime.now())
                .metadata(Map.of("priority", "critical", "escalation", "director"))
                .build();
    }

    public static com.supplychain.common.config.ApprovalTimeoutConfig buildNormalTimeoutConfig() {
        return com.supplychain.common.config.ApprovalTimeoutConfig.builder()
                .configId("cfg_timeout_custom_normal")
                .orderType("purchase")
                .timeoutMinutes(90)
                .description("普通采购 - 自定义90分钟超时提醒")
                .notificationIntervalMinutes(45)
                .maxNotifications(3)
                .enabled(true)
                .createdAt(LocalDateTime.now().minusDays(14))
                .updatedAt(LocalDateTime.now().minusDays(3))
                .metadata(Map.of("priority", "normal", "escalation", "manager"))
                .build();
    }

    public static com.supplychain.common.config.ApprovalTimeoutConfig buildLowPriorityTimeoutConfig() {
        return com.supplychain.common.config.ApprovalTimeoutConfig.builder()
                .configId("cfg_timeout_custom_low")
                .orderType("low_priority_purchase")
                .timeoutMinutes(240)
                .description("低优先级采购 - 自定义4小时超时提醒")
                .notificationIntervalMinutes(120)
                .maxNotifications(2)
                .enabled(true)
                .createdAt(LocalDateTime.now().minusDays(30))
                .updatedAt(LocalDateTime.now().minusDays(10))
                .metadata(Map.of("priority", "low", "escalation", "supervisor"))
                .build();
    }

    public static com.supplychain.common.config.InventorySyncStrategyConfig buildRealTimeStrategyConfig() {
        return com.supplychain.common.config.InventorySyncStrategyConfig.builder()
                .strategyId("strategy_custom_real_time")
                .strategyName("实时同步策略")
                .description("低频变动 - 实时同步确保数据一致性")
                .minFrequency(0)
                .maxFrequency(9)
                .syncMode(com.supplychain.common.config.InventorySyncStrategyConfig.SyncMode.REAL_TIME)
                .batchSize(1)
                .syncIntervalSeconds(0)
                .enabled(true)
                .params(Map.of("mergeWindow", "0s", "priority", "high"))
                .build();
    }

    public static com.supplychain.common.config.InventorySyncStrategyConfig buildBatchStrategyConfig() {
        return com.supplychain.common.config.InventorySyncStrategyConfig.builder()
                .strategyId("strategy_custom_batch")
                .strategyName("批量同步策略")
                .description("高频变动 - 批量合并减少同步开销")
                .minFrequency(100)
                .maxFrequency(Integer.MAX_VALUE)
                .syncMode(com.supplychain.common.config.InventorySyncStrategyConfig.SyncMode.BATCH)
                .batchSize(100)
                .syncIntervalSeconds(120)
                .enabled(true)
                .params(Map.of("mergeWindow", "120s", "priority", "low"))
                .build();
    }

    public static com.supplychain.common.config.InventorySyncStrategyConfig buildHybridStrategyConfig() {
        return com.supplychain.common.config.InventorySyncStrategyConfig.builder()
                .strategyId("strategy_custom_hybrid")
                .strategyName("混合同步策略")
                .description("中高频变动 - 根据数据量动态选择策略")
                .minFrequency(50)
                .maxFrequency(99)
                .syncMode(com.supplychain.common.config.InventorySyncStrategyConfig.SyncMode.HYBRID)
                .batchSize(50)
                .syncIntervalSeconds(60)
                .enabled(true)
                .params(Map.of("mergeWindow", "60s", "priority", "medium"))
                .build();
    }

    public static com.supplychain.common.config.ApprovalWorkflowConfig buildStandardWorkflowConfig() {
        List<com.supplychain.common.config.ApprovalWorkflowConfig.ApprovalStep> steps = new ArrayList<>();
        steps.add(com.supplychain.common.config.ApprovalWorkflowConfig.ApprovalStep.builder()
                .stepId("wf_step_1")
                .stepName("主管审批")
                .stepOrder(1)
                .approverRole("supervisor")
                .approverUsers(Arrays.asList("user_001", "user_002"))
                .approvalType(com.supplychain.common.config.ApprovalWorkflowConfig.ApprovalType.OR)
                .minAmount(0)
                .maxAmount(100000)
                .timeoutMinutes(60)
                .skippable(false)
                .statusBefore("pending_approval")
                .statusAfter("confirmed")
                .onApproveAction("confirm_order")
                .onRejectAction("reject_order")
                .build());

        steps.add(com.supplychain.common.config.ApprovalWorkflowConfig.ApprovalStep.builder()
                .stepId("wf_step_2")
                .stepName("经理审批")
                .stepOrder(2)
                .approverRole("manager")
                .approverUsers(Arrays.asList("user_101", "user_102"))
                .approvalType(com.supplychain.common.config.ApprovalWorkflowConfig.ApprovalType.SINGLE)
                .minAmount(100000)
                .maxAmount(500000)
                .timeoutMinutes(180)
                .skippable(false)
                .statusBefore("pending_approval")
                .statusAfter("confirmed")
                .onApproveAction("confirm_order")
                .onRejectAction("reject_order")
                .build());

        return com.supplychain.common.config.ApprovalWorkflowConfig.builder()
                .workflowId("wf_custom_standard")
                .workflowName("自定义标准采购审批流程")
                .orderType("purchase")
                .description("标准采购订单审批流程 - 自定义版本")
                .enabled(true)
                .version(2)
                .steps(steps)
                .conditions(Map.of("autoApproval", false, "requireAttachment", true))
                .notifications(Map.of("timeoutNotify", true, "approveNotify", true, "rejectNotify", true))
                .createdBy("admin")
                .createdAt(LocalDateTime.now().minusDays(30))
                .updatedAt(LocalDateTime.now())
                .build();
    }

    public static com.supplychain.common.config.ApprovalWorkflowConfig buildFastTrackWorkflowConfig() {
        List<com.supplychain.common.config.ApprovalWorkflowConfig.ApprovalStep> steps = new ArrayList<>();
        steps.add(com.supplychain.common.config.ApprovalWorkflowConfig.ApprovalStep.builder()
                .stepId("wf_fast_step_1")
                .stepName("快速审批")
                .stepOrder(1)
                .approverRole("manager")
                .approverUsers(Arrays.asList("user_101", "user_102", "user_201"))
                .approvalType(com.supplychain.common.config.ApprovalWorkflowConfig.ApprovalType.OR)
                .minAmount(0)
                .maxAmount(Double.MAX_VALUE)
                .timeoutMinutes(15)
                .skippable(false)
                .statusBefore("pending_approval")
                .statusAfter("confirmed")
                .onApproveAction("confirm_order")
                .onRejectAction("reject_order")
                .metadata(Map.of("priority", "critical", "escalation", true))
                .build());

        return com.supplychain.common.config.ApprovalWorkflowConfig.builder()
                .workflowId("wf_custom_fast")
                .workflowName("自定义快速采购审批流程")
                .orderType("urgent_purchase")
                .description("快速采购订单审批流程 - 快速通道版本")
                .enabled(true)
                .version(1)
                .steps(steps)
                .conditions(Map.of("autoApproval", false, "requireAttachment", false, "skipFirstStep", true))
                .notifications(Map.of("timeoutNotify", true, "approveNotify", true, "rejectNotify", true, "escalationNotify", true))
                .createdBy("admin")
                .createdAt(LocalDateTime.now().minusDays(15))
                .updatedAt(LocalDateTime.now())
                .build();
    }

    public static com.supplychain.common.config.ApprovalWorkflowConfig buildMultiStepWorkflowConfig() {
        List<com.supplychain.common.config.ApprovalWorkflowConfig.ApprovalStep> steps = new ArrayList<>();
        steps.add(com.supplychain.common.config.ApprovalWorkflowConfig.ApprovalStep.builder()
                .stepId("wf_multi_step_1")
                .stepName("采购审批")
                .stepOrder(1)
                .approverRole("supervisor")
                .approverUsers(Arrays.asList("user_001", "user_002"))
                .approvalType(com.supplychain.common.config.ApprovalWorkflowConfig.ApprovalType.OR)
                .minAmount(0)
                .maxAmount(50000)
                .timeoutMinutes(120)
                .skippable(false)
                .statusBefore("pending_approval")
                .statusAfter("manager_review")
                .onApproveAction("forward_to_manager")
                .onRejectAction("reject_order")
                .build());

        steps.add(com.supplychain.common.config.ApprovalWorkflowConfig.ApprovalStep.builder()
                .stepId("wf_multi_step_2")
                .stepName("经理审核")
                .stepOrder(2)
                .approverRole("manager")
                .approverUsers(Arrays.asList("user_101"))
                .approvalType(com.supplychain.common.config.ApprovalWorkflowConfig.ApprovalType.SINGLE)
                .minAmount(50000)
                .maxAmount(200000)
                .timeoutMinutes(240)
                .skippable(false)
                .statusBefore("manager_review")
                .statusAfter("director_review")
                .onApproveAction("forward_to_director")
                .onRejectAction("reject_order")
                .build());

        steps.add(com.supplychain.common.config.ApprovalWorkflowConfig.ApprovalStep.builder()
                .stepId("wf_multi_step_3")
                .stepName("总监审批")
                .stepOrder(3)
                .approverRole("director")
                .approverUsers(Arrays.asList("user_201"))
                .approvalType(com.supplychain.common.config.ApprovalWorkflowConfig.ApprovalType.SINGLE)
                .minAmount(200000)
                .maxAmount(Double.MAX_VALUE)
                .timeoutMinutes(480)
                .skippable(false)
                .statusBefore("director_review")
                .statusAfter("confirmed")
                .onApproveAction("confirm_order")
                .onRejectAction("reject_order")
                .build());

        return com.supplychain.common.config.ApprovalWorkflowConfig.builder()
                .workflowId("wf_custom_multi")
                .workflowName("自定义多级采购审批流程")
                .orderType("large_purchase")
                .description("大额采购订单多级审批流程 - 主管→经理→总监")
                .enabled(true)
                .version(1)
                .steps(steps)
                .conditions(Map.of("autoApproval", false, "requireAttachment", true, "multiStep", true))
                .notifications(Map.of("timeoutNotify", true, "approveNotify", true, "rejectNotify", true))
                .createdBy("admin")
                .createdAt(LocalDateTime.now().minusDays(60))
                .updatedAt(LocalDateTime.now().minusDays(7))
                .build();
    }
}
