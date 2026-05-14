package com.stockmgmt.service;

import com.stockmgmt.dto.*;
import com.stockmgmt.entity.Stock;
import com.stockmgmt.entity.StockBatch;
import com.stockmgmt.entity.StockLocation;
import com.stockmgmt.entity.StockRecord;
import com.stockmgmt.enums.OperationType;
import com.stockmgmt.exception.BusinessException;
import com.stockmgmt.repository.StockRecordRepository;
import com.stockmgmt.repository.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class InboundOutboundService {

    private static final Logger logger = LoggerFactory.getLogger(InboundOutboundService.class);

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private StockRecordRepository recordRepository;

    @Autowired
    private BatchService batchService;

    @Autowired
    private LocationService locationService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private WarningService warningService;

    @Autowired
    private LockService lockService;

    @Autowired
    private AsyncStockService asyncStockService;

    @Autowired
    private WarningThresholdConfigService thresholdConfigService;

    @Transactional(rollbackFor = Exception.class)
    public InboundResponse inbound(InboundRequest request) {
        logger.info("入库操作，商品ID: {}, 数量: {}", request.getProductId(), request.getQuantity());

        String warehouseId = request.getWarehouseId() != null ? request.getWarehouseId() : "warehouse_main";

        if (request.getAsync() != null && request.getAsync()) {
            String taskId = asyncStockService.submitInboundTask(request);
            return InboundResponse.builder()
                    .taskId(taskId)
                    .async(true)
                    .build();
        }

        return doInbound(request, warehouseId);
    }

    private InboundResponse doInbound(InboundRequest request, String warehouseId) {
        StockLocation location = locationService.getOrCreateLocation(warehouseId, request.getLocationId());

        Optional<Stock> existingStock = stockRepository.findByProductIdAndWarehouseIdWithLock(
                request.getProductId(), warehouseId);

        Stock stock;
        int beforeQuantity;
        if (existingStock.isPresent()) {
            stock = existingStock.get();
            beforeQuantity = stock.getCurrentQuantity();
            stock.setCurrentQuantity(beforeQuantity + request.getQuantity());
            stock.setAvailableQuantity(stock.getAvailableQuantity() + request.getQuantity());
            if (request.getCostPrice() != null) {
                stock.setCostPrice(request.getCostPrice());
            }
            if (request.getProductName() != null) {
                stock.setProductName(request.getProductName());
            }
            if (request.getSkuId() != null) {
                stock.setSkuId(request.getSkuId());
            }
            if (request.getUnit() != null) {
                stock.setUnit(request.getUnit());
            }
            if (request.getWarningThreshold() != null) {
                stock.setWarningThreshold(request.getWarningThreshold());
            }
            if (request.getOverstockThreshold() != null) {
                stock.setOverstockThreshold(request.getOverstockThreshold());
            }
            stock.setLocationId(location.getLocationId());
        } else {
            stock = new Stock();
            stock.setProductId(request.getProductId());
            stock.setProductName(request.getProductName());
            stock.setSkuId(request.getSkuId());
            stock.setWarehouseId(warehouseId);
            stock.setLocationId(location.getLocationId());
            stock.setUnit(request.getUnit() != null ? request.getUnit() : "件");
            stock.setCostPrice(request.getCostPrice());
            stock.setCurrentQuantity(request.getQuantity());
            stock.setAvailableQuantity(request.getQuantity());
            stock.setLockedQuantity(0);
            thresholdConfigService.applyThresholds(stock);
            beforeQuantity = 0;
        }

        Stock savedStock = stockRepository.save(stock);

        StockBatch batch = batchService.createOrGetBatch(request);

        StockRecord record = new StockRecord();
        record.setStockId(savedStock.getStockId());
        record.setOperationType(OperationType.INBOUND);
        record.setQuantity(request.getQuantity());
        record.setBatchId(batch.getBatchId());
        record.setLocationId(location.getLocationId());
        record.setOperator(request.getOperator() != null ? request.getOperator() : "system");
        record.setReferenceNo(request.getReferenceNo());
        record.setRemark(request.getRemark());
        StockRecord savedRecord = recordRepository.save(record);

        historyService.recordHistory(savedStock, OperationType.INBOUND, request.getQuantity(),
                beforeQuantity, savedStock.getCurrentQuantity(), request.getOperator(),
                request.getReferenceNo(), request.getRemark());

        warningService.checkAndTriggerWarning(savedStock);

        logger.info("入库操作成功，stockId: {}, recordId: {}", savedStock.getStockId(), savedRecord.getRecordId());

        return InboundResponse.builder()
                .stockId(savedStock.getStockId())
                .recordId(savedRecord.getRecordId())
                .batchId(batch.getBatchId())
                .currentQuantity(savedStock.getCurrentQuantity())
                .availableQuantity(savedStock.getAvailableQuantity())
                .build();
    }

    @Transactional(rollbackFor = Exception.class)
    public OutboundResponse outbound(OutboundRequest request) {
        logger.info("出库操作，商品ID: {}, 数量: {}, 紧急程度: {}",
                request.getProductId(), request.getQuantity(), request.getUrgencyLevel());

        String warehouseId = request.getWarehouseId() != null ? request.getWarehouseId() : "warehouse_main";

        if (request.getAsync() != null && request.getAsync()) {
            String taskId = asyncStockService.submitOutboundTask(request);
            return OutboundResponse.builder()
                    .taskId(taskId)
                    .async(true)
                    .build();
        }

        return doOutbound(request, warehouseId);
    }

    private OutboundResponse doOutbound(OutboundRequest request, String warehouseId) {
        Stock stock = stockRepository.findByProductIdAndWarehouseIdWithLock(request.getProductId(), warehouseId)
                .orElseThrow(() -> BusinessException.of("库存记录不存在: " + request.getProductId()));

        if (stock.getAvailableQuantity() < request.getQuantity()) {
            throw BusinessException.of(400, "库存不足，当前可用: " + stock.getAvailableQuantity());
        }

        String lockId = null;
        if (request.getNeedLock() != null && request.getNeedLock()) {
            LockRequest lockRequest = new LockRequest();
            lockRequest.setProductId(request.getProductId());
            lockRequest.setWarehouseId(warehouseId);
            lockRequest.setQuantity(request.getQuantity());
            lockRequest.setReferenceNo(request.getReferenceNo());
            lockRequest.setOperator(request.getOperator());
            lockRequest.setUrgencyLevel(request.getUrgencyLevel());
            LockResponse lockResponse = lockService.lockStock(lockRequest);
            lockId = lockResponse.getLockId();
            stock = stockRepository.findByIdWithLock(stock.getStockId()).orElse(stock);
        }

        int beforeQuantity = stock.getCurrentQuantity();
        stock.setCurrentQuantity(beforeQuantity - request.getQuantity());
        if (lockId != null) {
            lockService.consumeLock(lockId);
        } else {
            stock.setAvailableQuantity(stock.getAvailableQuantity() - request.getQuantity());
        }
        Stock savedStock = stockRepository.save(stock);

        StockRecord record = new StockRecord();
        record.setStockId(savedStock.getStockId());
        record.setOperationType(OperationType.OUTBOUND);
        record.setQuantity(request.getQuantity());
        record.setLocationId(stock.getLocationId());
        record.setOperator(request.getOperator() != null ? request.getOperator() : "system");
        record.setReferenceNo(request.getReferenceNo());
        record.setRemark(request.getRemark());
        StockRecord savedRecord = recordRepository.save(record);

        historyService.recordHistory(savedStock, OperationType.OUTBOUND, -request.getQuantity(),
                beforeQuantity, savedStock.getCurrentQuantity(), request.getOperator(),
                request.getReferenceNo(), request.getRemark());

        warningService.checkAndTriggerWarning(savedStock);

        logger.info("出库操作成功，stockId: {}, recordId: {}", savedStock.getStockId(), savedRecord.getRecordId());

        return OutboundResponse.builder()
                .stockId(savedStock.getStockId())
                .recordId(savedRecord.getRecordId())
                .lockId(lockId)
                .currentQuantity(savedStock.getCurrentQuantity())
                .availableQuantity(savedStock.getAvailableQuantity())
                .lockedQuantity(savedStock.getLockedQuantity())
                .build();
    }

    @Transactional(rollbackFor = Exception.class)
    public TransferResponse transfer(TransferRequest request) {
        logger.info("调拨操作，商品ID: {}, 数量: {}, 从: {}, 到: {}",
                request.getProductId(), request.getQuantity(),
                request.getFromWarehouseId(), request.getToWarehouseId());

        if (request.getFromWarehouseId().equals(request.getToWarehouseId())) {
            throw BusinessException.of("源仓库和目标仓库不能相同");
        }

        Stock fromStock = stockRepository.findByProductIdAndWarehouseIdWithLock(
                request.getProductId(), request.getFromWarehouseId())
                .orElseThrow(() -> BusinessException.of("源仓库库存不存在: " + request.getProductId()));

        if (fromStock.getAvailableQuantity() < request.getQuantity()) {
            throw BusinessException.of(400, "源仓库库存不足，当前可用: " + fromStock.getAvailableQuantity());
        }

        int fromBeforeQuantity = fromStock.getCurrentQuantity();
        fromStock.setCurrentQuantity(fromBeforeQuantity - request.getQuantity());
        fromStock.setAvailableQuantity(fromStock.getAvailableQuantity() - request.getQuantity());
        Stock savedFromStock = stockRepository.save(fromStock);

        StockRecord outRecord = new StockRecord();
        outRecord.setStockId(savedFromStock.getStockId());
        outRecord.setOperationType(OperationType.OUTBOUND);
        outRecord.setQuantity(request.getQuantity());
        outRecord.setLocationId(savedFromStock.getLocationId());
        outRecord.setOperator(request.getOperator() != null ? request.getOperator() : "system");
        outRecord.setReferenceNo(request.getReferenceNo());
        outRecord.setRemark("调拨出库至: " + request.getToWarehouseId());
        recordRepository.save(outRecord);

        historyService.recordHistory(savedFromStock, OperationType.TRANSFER, -request.getQuantity(),
                fromBeforeQuantity, savedFromStock.getCurrentQuantity(), request.getOperator(),
                request.getReferenceNo(), "调拨出库");

        Optional<Stock> existingToStock = stockRepository.findByProductIdAndWarehouseIdWithLock(
                request.getProductId(), request.getToWarehouseId());

        Stock toStock;
        int toBeforeQuantity;
        if (existingToStock.isPresent()) {
            toStock = existingToStock.get();
            toBeforeQuantity = toStock.getCurrentQuantity();
            toStock.setCurrentQuantity(toBeforeQuantity + request.getQuantity());
            toStock.setAvailableQuantity(toStock.getAvailableQuantity() + request.getQuantity());
        } else {
            toStock = new Stock();
            toStock.setProductId(request.getProductId());
            toStock.setProductName(fromStock.getProductName());
            toStock.setSkuId(fromStock.getSkuId());
            toStock.setWarehouseId(request.getToWarehouseId());
            toStock.setLocationId(request.getToLocationId());
            toStock.setUnit(fromStock.getUnit());
            toStock.setCostPrice(fromStock.getCostPrice());
            toStock.setCurrentQuantity(request.getQuantity());
            toStock.setAvailableQuantity(request.getQuantity());
            toStock.setLockedQuantity(0);
            thresholdConfigService.applyThresholds(toStock);
            toBeforeQuantity = 0;
        }
        Stock savedToStock = stockRepository.save(toStock);

        StockRecord inRecord = new StockRecord();
        inRecord.setStockId(savedToStock.getStockId());
        inRecord.setOperationType(OperationType.INBOUND);
        inRecord.setQuantity(request.getQuantity());
        inRecord.setLocationId(savedToStock.getLocationId());
        inRecord.setOperator(request.getOperator() != null ? request.getOperator() : "system");
        inRecord.setReferenceNo(request.getReferenceNo());
        inRecord.setRemark("调拨入库从: " + request.getFromWarehouseId());
        recordRepository.save(inRecord);

        historyService.recordHistory(savedToStock, OperationType.TRANSFER, request.getQuantity(),
                toBeforeQuantity, savedToStock.getCurrentQuantity(), request.getOperator(),
                request.getReferenceNo(), "调拨入库");

        warningService.checkAndTriggerWarning(savedFromStock);
        warningService.checkAndTriggerWarning(savedToStock);

        logger.info("调拨操作成功，源stockId: {}, 目标stockId: {}",
                savedFromStock.getStockId(), savedToStock.getStockId());

        return TransferResponse.builder()
                .fromStockId(savedFromStock.getStockId())
                .toStockId(savedToStock.getStockId())
                .outRecordId(outRecord.getRecordId())
                .inRecordId(inRecord.getRecordId())
                .fromQuantity(savedFromStock.getCurrentQuantity())
                .toQuantity(savedToStock.getCurrentQuantity())
                .build();
    }
}
