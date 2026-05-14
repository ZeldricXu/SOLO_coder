package com.stockmgmt.service;

import com.stockmgmt.dto.LockRequest;
import com.stockmgmt.dto.LockResponse;
import com.stockmgmt.entity.LockTimeoutConfig;
import com.stockmgmt.entity.Stock;
import com.stockmgmt.entity.StockLock;
import com.stockmgmt.enums.LockStatus;
import com.stockmgmt.enums.OperationType;
import com.stockmgmt.exception.BusinessException;
import com.stockmgmt.repository.StockLockRepository;
import com.stockmgmt.repository.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class LockService {

    private static final Logger logger = LoggerFactory.getLogger(LockService.class);

    @Autowired
    private StockLockRepository lockRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private LockTimeoutConfigService lockTimeoutConfigService;

    @Transactional(rollbackFor = Exception.class)
    public LockResponse lockStock(LockRequest request) {
        logger.info("锁定库存，商品ID: {}, 数量: {}, 紧急程度: {}",
                request.getProductId(), request.getQuantity(), request.getUrgencyLevel());

        String warehouseId = request.getWarehouseId() != null ? request.getWarehouseId() : "warehouse_main";
        String urgencyLevel = request.getUrgencyLevel() != null ?
                request.getUrgencyLevel() : LockTimeoutConfigService.URGENCY_NORMAL;

        LockTimeoutConfig timeoutConfig = lockTimeoutConfigService.getConfig(
                request.getProductId(), warehouseId, urgencyLevel);

        Stock stock = stockRepository.findByProductIdAndWarehouseIdWithLock(request.getProductId(), warehouseId)
                .orElseThrow(() -> BusinessException.of("库存不存在: " + request.getProductId()));

        if (stock.getAvailableQuantity() < request.getQuantity()) {
            throw BusinessException.of("可用库存不足，当前可用: " + stock.getAvailableQuantity());
        }

        int beforeQuantity = stock.getAvailableQuantity();
        stock.setAvailableQuantity(stock.getAvailableQuantity() - request.getQuantity());
        stock.setLockedQuantity(stock.getLockedQuantity() + request.getQuantity());
        stockRepository.save(stock);

        StockLock lock = new StockLock();
        lock.setStockId(stock.getStockId());
        lock.setProductId(stock.getProductId());
        lock.setLockedQuantity(request.getQuantity());
        lock.setStatus(LockStatus.LOCKED);
        lock.setReferenceNo(request.getReferenceNo());
        lock.setOperator(request.getOperator() != null ? request.getOperator() : "system");
        lock.setUrgencyLevel(urgencyLevel);
        lock.setTimeoutSeconds(timeoutConfig.getTimeoutSeconds());

        LocalDateTime expireTime = LocalDateTime.now().plusSeconds(timeoutConfig.getTimeoutSeconds());
        lock.setExpireTime(expireTime);

        lock.setRemark(request.getRemark());

        StockLock savedLock = lockRepository.save(lock);

        historyService.recordHistory(stock, OperationType.LOCK, request.getQuantity(),
                beforeQuantity, stock.getAvailableQuantity(), request.getOperator(),
                request.getReferenceNo(), request.getRemark());

        logger.info("库存锁定成功，lockId: {}, 超时时间: {}秒, 紧急程度: {}",
                savedLock.getLockId(), timeoutConfig.getTimeoutSeconds(), urgencyLevel);

        return LockResponse.builder()
                .lockId(savedLock.getLockId())
                .stockId(stock.getStockId())
                .lockedQuantity(savedLock.getLockedQuantity())
                .availableQuantity(stock.getAvailableQuantity())
                .timeoutSeconds(timeoutConfig.getTimeoutSeconds())
                .urgencyLevel(urgencyLevel)
                .build();
    }

    @Transactional(rollbackFor = Exception.class)
    public void unlockStock(String lockId, String operator, String remark) {
        logger.info("解锁库存，lockId: {}", lockId);

        StockLock lock = lockRepository.findByIdWithLock(lockId)
                .orElseThrow(() -> BusinessException.of("锁定记录不存在: " + lockId));

        if (lock.getStatus() != LockStatus.LOCKED) {
            throw BusinessException.of("锁定状态不正确，当前状态: " + lock.getStatus());
        }

        Stock stock = stockRepository.findByIdWithLock(lock.getStockId())
                .orElseThrow(() -> BusinessException.of("库存不存在: " + lock.getStockId()));

        int beforeQuantity = stock.getAvailableQuantity();
        stock.setAvailableQuantity(stock.getAvailableQuantity() + lock.getLockedQuantity());
        stock.setLockedQuantity(stock.getLockedQuantity() - lock.getLockedQuantity());
        stockRepository.save(stock);

        lock.setStatus(LockStatus.RELEASED);
        lock.setReleasedAt(LocalDateTime.now());
        lockRepository.save(lock);

        historyService.recordHistory(stock, OperationType.UNLOCK, -lock.getLockedQuantity(),
                beforeQuantity, stock.getAvailableQuantity(), operator != null ? operator : "system",
                lock.getReferenceNo(), remark);

        logger.info("库存解锁成功，lockId: {}", lockId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void unlockStockByReferenceNo(String referenceNo, String operator, String remark) {
        logger.info("根据参考单号解锁库存，referenceNo: {}", referenceNo);

        StockLock lock = lockRepository.findFirstByReferenceNoAndStatusOrderByLockedAtDesc(referenceNo, LockStatus.LOCKED)
                .orElseThrow(() -> BusinessException.of("未找到锁定记录，参考单号: " + referenceNo));

        unlockStock(lock.getLockId(), operator, remark);
    }

    @Transactional(rollbackFor = Exception.class)
    public void consumeLock(String lockId) {
        logger.info("消耗锁定库存，lockId: {}", lockId);

        StockLock lock = lockRepository.findByIdWithLock(lockId)
                .orElseThrow(() -> BusinessException.of("锁定记录不存在: " + lockId));

        if (lock.getStatus() != LockStatus.LOCKED) {
            throw BusinessException.of("锁定状态不正确，当前状态: " + lock.getStatus());
        }

        Stock stock = stockRepository.findByIdWithLock(lock.getStockId())
                .orElseThrow(() -> BusinessException.of("库存不存在: " + lock.getStockId()));

        stock.setLockedQuantity(stock.getLockedQuantity() - lock.getLockedQuantity());
        stockRepository.save(stock);

        lock.setStatus(LockStatus.RELEASED);
        lock.setReleasedAt(LocalDateTime.now());
        lockRepository.save(lock);

        logger.info("锁定库存消耗成功，lockId: {}", lockId);
    }

    public StockLock getLockById(String lockId) {
        return lockRepository.findById(lockId)
                .orElseThrow(() -> BusinessException.of("锁定记录不存在: " + lockId));
    }

    public List<StockLock> getLocksByStockId(String stockId) {
        return lockRepository.findByStockId(stockId);
    }

    public List<StockLock> getActiveLocks(String stockId) {
        return lockRepository.findByStockIdAndStatus(stockId, LockStatus.LOCKED);
    }

    public List<StockLock> getLocksByStatus(LockStatus status) {
        return lockRepository.findByStatus(status);
    }

    public Integer getTotalLockedQuantity(String stockId) {
        Integer result = lockRepository.sumLockedQuantityByStockIdAndStatus(stockId, LockStatus.LOCKED);
        return result != null ? result : 0;
    }

    public int getTimeoutSeconds(String productId, String warehouseId, String urgencyLevel) {
        return lockTimeoutConfigService.getTimeoutSeconds(productId, warehouseId, urgencyLevel);
    }

    public int getMaxRetryTimes(String productId, String warehouseId, String urgencyLevel) {
        return lockTimeoutConfigService.getMaxRetryTimes(productId, warehouseId, urgencyLevel);
    }

    public LockTimeoutConfig getLockTimeoutConfig(String productId, String warehouseId, String urgencyLevel) {
        return lockTimeoutConfigService.getConfig(productId, warehouseId, urgencyLevel);
    }

    @Transactional(rollbackFor = Exception.class)
    public int cleanExpiredLocks() {
        logger.info("清理过期锁定记录");

        LocalDateTime now = LocalDateTime.now();
        List<StockLock> expiredLocks = lockRepository.findExpiredLocks(LockStatus.LOCKED, now);

        int count = 0;
        for (StockLock lock : expiredLocks) {
            try {
                unlockStock(lock.getLockId(), "system", "自动解锁-过期");
                count++;
            } catch (Exception e) {
                logger.error("解锁过期锁定失败，lockId: {}", lock.getLockId(), e);
            }
        }

        logger.info("清理过期锁定记录完成，数量: {}", count);
        return count;
    }
}
