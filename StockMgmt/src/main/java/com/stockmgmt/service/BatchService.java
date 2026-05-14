package com.stockmgmt.service;

import com.stockmgmt.dto.InboundRequest;
import com.stockmgmt.entity.StockBatch;
import com.stockmgmt.exception.BusinessException;
import com.stockmgmt.repository.StockBatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
public class BatchService {

    private static final Logger logger = LoggerFactory.getLogger(BatchService.class);

    @Autowired
    private StockBatchRepository batchRepository;

    @Transactional(rollbackFor = Exception.class)
    public StockBatch createOrGetBatch(InboundRequest request) {
        logger.info("创建或获取批次，批次号: {}", request.getBatchNo());

        Optional<StockBatch> existingBatch = batchRepository.findByBatchNo(request.getBatchNo());
        if (existingBatch.isPresent()) {
            StockBatch batch = existingBatch.get();
            batch.setRemainingQuantity(batch.getRemainingQuantity() + request.getQuantity());
            logger.info("批次已存在，更新剩余数量: {}", batch.getBatchNo());
            return batchRepository.save(batch);
        }

        StockBatch batch = new StockBatch();
        batch.setProductId(request.getProductId());
        batch.setBatchNo(request.getBatchNo());
        batch.setBatchQuantity(request.getQuantity());
        batch.setRemainingQuantity(request.getQuantity());
        batch.setWarehouseId(request.getWarehouseId() != null ? request.getWarehouseId() : "warehouse_main");
        batch.setProductionDate(request.getProductionDate() != null ? request.getProductionDate() : LocalDate.now());
        batch.setExpireDate(request.getExpireDate());
        batch.setSupplier(request.getSupplier());

        StockBatch saved = batchRepository.save(batch);
        logger.info("批次创建成功，批次ID: {}", saved.getBatchId());
        return saved;
    }

    @Transactional(rollbackFor = Exception.class)
    public StockBatch consumeBatch(String batchNo, Integer quantity) {
        logger.info("消耗批次，批次号: {}, 数量: {}", batchNo, quantity);

        StockBatch batch = batchRepository.findByBatchNo(batchNo)
                .orElseThrow(() -> BusinessException.of("批次不存在: " + batchNo));

        if (batch.getRemainingQuantity() < quantity) {
            throw BusinessException.of("批次库存不足: " + batchNo);
        }

        batch.setRemainingQuantity(batch.getRemainingQuantity() - quantity);
        return batchRepository.save(batch);
    }

    public StockBatch getBatchById(String batchId) {
        return batchRepository.findById(batchId)
                .orElseThrow(() -> BusinessException.of("批次不存在: " + batchId));
    }

    public StockBatch getBatchByNo(String batchNo) {
        return batchRepository.findByBatchNo(batchNo)
                .orElseThrow(() -> BusinessException.of("批次不存在: " + batchNo));
    }

    public List<StockBatch> getBatchesByProductId(String productId) {
        return batchRepository.findByProductId(productId);
    }

    public List<StockBatch> getAvailableBatches(String productId) {
        return batchRepository.findAvailableBatchesByProductId(productId);
    }

    public List<StockBatch> getExpiringBatches(LocalDate expireDate) {
        return batchRepository.findExpiringBatches(expireDate);
    }

    public List<StockBatch> getBatchesByWarehouse(String warehouseId) {
        return batchRepository.findByWarehouseId(warehouseId);
    }
}
