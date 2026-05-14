package com.stockmgmt.service;

import com.stockmgmt.entity.Stock;
import com.stockmgmt.entity.StockHistory;
import com.stockmgmt.enums.OperationType;
import com.stockmgmt.repository.StockHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class HistoryService {

    private static final Logger logger = LoggerFactory.getLogger(HistoryService.class);

    @Autowired
    private StockHistoryRepository historyRepository;

    @Transactional(rollbackFor = Exception.class)
    public StockHistory recordHistory(Stock stock, OperationType operationType, Integer quantityChange,
                                      Integer beforeQuantity, Integer afterQuantity, String operator,
                                      String referenceNo, String remark) {
        logger.info("记录库存历史，stockId: {}, 操作类型: {}", stock.getStockId(), operationType);

        StockHistory history = new StockHistory();
        history.setStockId(stock.getStockId());
        history.setProductId(stock.getProductId());
        history.setOperationType(operationType);
        history.setQuantityChange(quantityChange);
        history.setBeforeQuantity(beforeQuantity);
        history.setAfterQuantity(afterQuantity);
        history.setOperator(operator != null ? operator : "system");
        history.setReferenceNo(referenceNo);
        history.setRemark(remark);

        StockHistory saved = historyRepository.save(history);
        logger.info("库存历史记录成功，historyId: {}", saved.getHistoryId());
        return saved;
    }

    public List<StockHistory> getHistoryByStockId(String stockId) {
        return historyRepository.findByStockIdOrderByOperationTimeDesc(stockId);
    }

    public List<StockHistory> getHistoryByProductId(String productId) {
        return historyRepository.findByProductIdOrderByOperationTimeDesc(productId);
    }

    public List<StockHistory> getHistoryByReferenceNo(String referenceNo) {
        return historyRepository.findByReferenceNo(referenceNo);
    }

    public List<StockHistory> getHistoryByTimeRange(String stockId, LocalDateTime startTime, LocalDateTime endTime) {
        return historyRepository.findByStockIdAndTimeRange(stockId, startTime, endTime);
    }

    public Page<StockHistory> getHistoryPage(String stockId, int pageNum, int pageSize) {
        Pageable pageable = PageRequest.of(pageNum - 1, pageSize, Sort.by(Sort.Direction.DESC, "operationTime"));
        org.springframework.data.jpa.domain.Specification<StockHistory> spec = null;

        if (stockId != null && !stockId.isEmpty()) {
            spec = (root, query, cb) -> cb.equal(root.get("stockId"), stockId);
        }

        if (spec != null) {
            return historyRepository.findAll(spec, pageable);
        }
        return historyRepository.findAll(pageable);
    }

    public Integer getTotalInbound(LocalDateTime startTime, LocalDateTime endTime) {
        Integer result = historyRepository.sumQuantityChangeByTypeAndTimeRange(OperationType.INBOUND, startTime, endTime);
        return result != null ? result : 0;
    }

    public Integer getTotalOutbound(LocalDateTime startTime, LocalDateTime endTime) {
        Integer result = historyRepository.sumQuantityChangeByTypeAndTimeRange(OperationType.OUTBOUND, startTime, endTime);
        return result != null ? Math.abs(result) : 0;
    }

    public long getOperationCount(OperationType operationType, LocalDateTime startTime, LocalDateTime endTime) {
        return historyRepository.countByTypeAndTimeRange(operationType, startTime, endTime);
    }
}
