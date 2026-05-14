package com.stockmgmt.builder;

import com.stockmgmt.dto.*;
import com.stockmgmt.entity.*;
import com.stockmgmt.enums.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public class TestDataBuilder {

    private static int counter = 0;

    private static synchronized int nextCounter() {
        return ++counter;
    }

    private static String generateId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public static Stock buildStock() {
        return buildStock(100, 100, 0);
    }

    public static Stock buildStock(int currentQuantity, int availableQuantity, int lockedQuantity) {
        Stock stock = new Stock();
        stock.setStockId("STOCK_" + nextCounter());
        stock.setProductId("PROD_" + nextCounter());
        stock.setProductName("测试商品_" + counter);
        stock.setSkuId("SKU_" + counter);
        stock.setCurrentQuantity(currentQuantity);
        stock.setAvailableQuantity(availableQuantity);
        stock.setLockedQuantity(lockedQuantity);
        stock.setWarehouseId("warehouse_main");
        stock.setLocationId("loc_zone_a_01");
        stock.setUnit("台");
        stock.setCostPrice(new BigDecimal("500.00"));
        stock.setWarningThreshold(10);
        stock.setOverstockThreshold(500);
        stock.setCreatedAt(LocalDateTime.now());
        stock.setUpdatedAt(LocalDateTime.now());
        stock.setVersion(0);
        return stock;
    }

    public static Stock buildStockWithThresholds(int currentQuantity, int warningThreshold, int overstockThreshold) {
        Stock stock = buildStock(currentQuantity, currentQuantity, 0);
        stock.setWarningThreshold(warningThreshold);
        stock.setOverstockThreshold(overstockThreshold);
        return stock;
    }

    public static Stock buildLowStock() {
        return buildStockWithThresholds(5, 10, 500);
    }

    public static Stock buildOverstock() {
        return buildStockWithThresholds(600, 10, 500);
    }

    public static Stock buildNormalStock() {
        return buildStockWithThresholds(100, 10, 500);
    }

    public static StockLock buildStockLock() {
        return buildStockLock(10, LockStatus.LOCKED);
    }

    public static StockLock buildStockLock(int quantity, LockStatus status) {
        StockLock lock = new StockLock();
        lock.setLockId("LOCK_" + nextCounter());
        lock.setStockId("STOCK_" + counter);
        lock.setProductId("PROD_" + counter);
        lock.setLockedQuantity(quantity);
        lock.setStatus(status);
        lock.setReferenceNo("REF_" + nextCounter());
        lock.setOperator("test_user");
        lock.setLockedAt(LocalDateTime.now());
        lock.setUpdatedAt(LocalDateTime.now());
        return lock;
    }

    public static StockLock buildExpiredLock() {
        StockLock lock = buildStockLock(10, LockStatus.LOCKED);
        lock.setExpireTime(LocalDateTime.now().minusHours(1));
        return lock;
    }

    public static StockWarning buildStockWarning() {
        return buildStockWarning(WarningType.LOW_STOCK, WarningLevel.HIGH);
    }

    public static StockWarning buildStockWarning(WarningType type, WarningLevel level) {
        StockWarning warning = new StockWarning();
        warning.setWarningId("WARNING_" + nextCounter());
        warning.setStockId("STOCK_" + counter);
        warning.setProductId("PROD_" + counter);
        warning.setProductName("测试商品_" + counter);
        warning.setWarningType(type);
        warning.setWarningLevel(level);
        warning.setCurrentQuantity(type == WarningType.LOW_STOCK ? 5 : 600);
        warning.setThreshold(type == WarningType.LOW_STOCK ? 10 : 500);
        warning.setStatus(WarningStatus.ACTIVE);
        warning.setTriggeredAt(LocalDateTime.now());
        return warning;
    }

    public static StockWarning buildActiveLowStockWarning() {
        return buildStockWarning(WarningType.LOW_STOCK, WarningLevel.HIGH);
    }

    public static StockWarning buildActiveOverstockWarning() {
        return buildStockWarning(WarningType.OVERSTOCK, WarningLevel.MEDIUM);
    }

    public static StockWarning buildHandledWarning() {
        StockWarning warning = buildStockWarning();
        warning.setStatus(WarningStatus.HANDLED);
        warning.setHandledAt(LocalDateTime.now());
        warning.setHandledBy("test_user");
        return warning;
    }

    public static StockCheck buildStockCheck() {
        return buildStockCheck(CheckStatus.PENDING, CheckType.FULL);
    }

    public static StockCheck buildStockCheck(CheckStatus status, CheckType type) {
        StockCheck check = new StockCheck();
        check.setCheckId("CHECK_" + nextCounter());
        check.setCheckNo("PD" + System.currentTimeMillis());
        check.setWarehouseId("warehouse_main");
        check.setCheckType(type);
        check.setCheckStatus(status);
        check.setCheckName("盘点任务_" + counter);
        check.setOperator("test_user");
        check.setTotalItems(100);
        check.setCheckedItems(0);
        check.setDifferenceCount(0);
        check.setCreatedAt(LocalDateTime.now());
        if (status == CheckStatus.PROCESSING) {
            check.setStartedAt(LocalDateTime.now());
        }
        if (status == CheckStatus.COMPLETED) {
            check.setStartedAt(LocalDateTime.now().minusHours(1));
            check.setCompletedAt(LocalDateTime.now());
        }
        return check;
    }

    public static StockCheckDiff buildStockCheckDiff() {
        return buildStockCheckDiff(-5, DiffHandleStatus.PENDING);
    }

    public static StockCheckDiff buildStockCheckDiff(int difference, DiffHandleStatus status) {
        StockCheckDiff diff = new StockCheckDiff();
        diff.setDiffId("DIFF_" + nextCounter());
        diff.setCheckId("CHECK_" + counter);
        diff.setStockId("STOCK_" + counter);
        diff.setProductId("PROD_" + counter);
        diff.setProductName("测试商品_" + counter);
        diff.setSystemQuantity(100);
        diff.setActualQuantity(100 + difference);
        diff.setDifference(difference);
        diff.setDiffReason(difference < 0 ? "破损丢失" : "盘盈");
        diff.setHandleStatus(status);
        diff.setCreatedAt(LocalDateTime.now());
        if (status == DiffHandleStatus.APPROVED) {
            diff.setApproveBy("approver");
            diff.setApproveAt(LocalDateTime.now());
        }
        if (status == DiffHandleStatus.PROCESSED) {
            diff.setApproveBy("approver");
            diff.setApproveAt(LocalDateTime.now().minusMinutes(30));
            diff.setHandledBy("handler");
            diff.setHandledAt(LocalDateTime.now());
        }
        return diff;
    }

    public static StockHistory buildStockHistory() {
        return buildStockHistory(OperationType.INBOUND, 50);
    }

    public static StockHistory buildStockHistory(OperationType type, int quantityChange) {
        StockHistory history = new StockHistory();
        history.setHistoryId("HIST_" + nextCounter());
        history.setStockId("STOCK_" + counter);
        history.setProductId("PROD_" + counter);
        history.setOperationType(type);
        history.setQuantityChange(quantityChange);
        history.setBeforeQuantity(100);
        history.setAfterQuantity(100 + quantityChange);
        history.setOperator("test_user");
        history.setReferenceNo("REF_" + counter);
        history.setOperationTime(LocalDateTime.now());
        return history;
    }

    public static StockRecord buildStockRecord() {
        return buildStockRecord(OperationType.INBOUND, 50);
    }

    public static StockRecord buildStockRecord(OperationType type, int quantity) {
        StockRecord record = new StockRecord();
        record.setRecordId("RECORD_" + nextCounter());
        record.setStockId("STOCK_" + counter);
        record.setOperationType(type);
        record.setQuantity(quantity);
        record.setBatchId("BATCH_" + counter);
        record.setLocationId("loc_zone_a_01");
        record.setOperator("test_user");
        record.setReferenceNo("REF_" + counter);
        record.setOperationTime(LocalDateTime.now());
        return record;
    }

    public static StockBatch buildStockBatch() {
        return buildStockBatch(50, 50);
    }

    public static StockBatch buildStockBatch(int batchQuantity, int remainingQuantity) {
        StockBatch batch = new StockBatch();
        batch.setBatchId("BATCH_" + nextCounter());
        batch.setProductId("PROD_" + counter);
        batch.setBatchQuantity(batchQuantity);
        batch.setRemainingQuantity(remainingQuantity);
        batch.setBatchNo("B" + System.currentTimeMillis() + counter);
        batch.setProductionDate(LocalDate.now().minusMonths(1));
        batch.setWarehouseId("warehouse_main");
        batch.setSupplier("测试供应商");
        batch.setCreatedAt(LocalDateTime.now());
        return batch;
    }

    public static StockLocation buildStockLocation() {
        StockLocation location = new StockLocation();
        location.setLocationId("LOC_" + nextCounter());
        location.setWarehouseId("warehouse_main");
        location.setLocationCode("loc_zone_a_0" + counter);
        location.setLocationName("A区0" + counter + "号库位");
        location.setZone("A");
        location.setAisle("01");
        location.setRack("0" + counter);
        location.setLevel("01");
        location.setCapacity(1000);
        location.setStatus("active");
        location.setCreatedAt(LocalDateTime.now());
        location.setUpdatedAt(LocalDateTime.now());
        return location;
    }

    public static InboundRequest buildInboundRequest() {
        return buildInboundRequest("PROD_001", 50, "BATCH_001", "loc_zone_a_01");
    }

    public static InboundRequest buildInboundRequest(String productId, int quantity, String batchNo, String locationId) {
        InboundRequest request = new InboundRequest();
        request.setProductId(productId);
        request.setProductName("测试商品");
        request.setSkuId("SKU_001");
        request.setQuantity(quantity);
        request.setBatchNo(batchNo);
        request.setLocationId(locationId);
        request.setWarehouseId("warehouse_main");
        request.setUnit("台");
        request.setCostPrice(new BigDecimal("500.00"));
        request.setProductionDate(LocalDate.now().minusMonths(1));
        request.setSupplier("测试供应商");
        request.setOperator("test_user");
        request.setReferenceNo("PO" + System.currentTimeMillis());
        request.setWarningThreshold(10);
        request.setOverstockThreshold(500);
        return request;
    }

    public static OutboundRequest buildOutboundRequest() {
        return buildOutboundRequest("PROD_001", 10);
    }

    public static OutboundRequest buildOutboundRequest(String productId, int quantity) {
        OutboundRequest request = new OutboundRequest();
        request.setProductId(productId);
        request.setQuantity(quantity);
        request.setWarehouseId("warehouse_main");
        request.setOperator("test_user");
        request.setReferenceNo("SO" + System.currentTimeMillis());
        request.setNeedLock(true);
        return request;
    }

    public static TransferRequest buildTransferRequest() {
        return buildTransferRequest("PROD_001", 20, "warehouse_main", "warehouse_branch");
    }

    public static TransferRequest buildTransferRequest(String productId, int quantity, String fromWarehouse, String toWarehouse) {
        TransferRequest request = new TransferRequest();
        request.setProductId(productId);
        request.setQuantity(quantity);
        request.setFromWarehouseId(fromWarehouse);
        request.setToWarehouseId(toWarehouse);
        request.setFromLocationId("loc_zone_a_01");
        request.setToLocationId("loc_zone_b_01");
        request.setOperator("test_user");
        request.setReferenceNo("TR" + System.currentTimeMillis());
        return request;
    }

    public static LockRequest buildLockRequest() {
        return buildLockRequest("PROD_001", 10, "ORDER_001");
    }

    public static LockRequest buildLockRequest(String productId, int quantity, String referenceNo) {
        LockRequest request = new LockRequest();
        request.setProductId(productId);
        request.setWarehouseId("warehouse_main");
        request.setQuantity(quantity);
        request.setReferenceNo(referenceNo);
        request.setOperator("test_user");
        request.setRemark("测试锁定");
        return request;
    }

    public static CheckCreateRequest buildCheckCreateRequest() {
        CheckCreateRequest request = new CheckCreateRequest();
        request.setWarehouseId("warehouse_main");
        request.setCheckType("full");
        request.setCheckName("月度全盘");
        request.setOperator("test_user");
        request.setRemark("测试盘点任务");
        return request;
    }

    public static CheckDiffRequest buildCheckDiffRequest() {
        return buildCheckDiffRequest("CHECK_001", "STOCK_001", 95, "破损丢失");
    }

    public static CheckDiffRequest buildCheckDiffRequest(String checkId, String stockId, int actualQuantity, String reason) {
        CheckDiffRequest request = new CheckDiffRequest();
        request.setCheckId(checkId);
        request.setStockId(stockId);
        request.setActualQuantity(actualQuantity);
        request.setDiffReason(reason);
        request.setOperator("test_user");
        return request;
    }

    public static WarningHandleRequest buildWarningHandleRequest() {
        WarningHandleRequest request = new WarningHandleRequest();
        request.setHandledBy("handler_user");
        request.setRemark("已通知采购部门补货");
        return request;
    }

    public static StockCreateRequest buildStockCreateRequest() {
        StockCreateRequest request = new StockCreateRequest();
        request.setProductId("PROD_" + nextCounter());
        request.setProductName("测试商品");
        request.setSkuId("SKU_001");
        request.setWarehouseId("warehouse_main");
        request.setLocationId("loc_zone_a_01");
        request.setUnit("台");
        request.setCostPrice(new BigDecimal("500.00"));
        request.setWarningThreshold(10);
        request.setOverstockThreshold(500);
        return request;
    }

    public static StockUpdateRequest buildStockUpdateRequest() {
        StockUpdateRequest request = new StockUpdateRequest();
        request.setProductName("更新后的商品名称");
        request.setLocationId("loc_zone_b_01");
        request.setCostPrice(new BigDecimal("600.00"));
        request.setWarningThreshold(20);
        request.setOverstockThreshold(600);
        return request;
    }

    public static void reset() {
        counter = 0;
    }
}
