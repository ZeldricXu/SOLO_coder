package com.stockmgmt.service;

import com.stockmgmt.dto.StockCreateRequest;
import com.stockmgmt.dto.StockUpdateRequest;
import com.stockmgmt.entity.Stock;
import com.stockmgmt.enums.OperationType;
import com.stockmgmt.exception.BusinessException;
import com.stockmgmt.repository.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class StockService {

    private static final Logger logger = LoggerFactory.getLogger(StockService.class);

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private HistoryService historyService;

    @Transactional(rollbackFor = Exception.class)
    public Stock createStock(StockCreateRequest request) {
        logger.info("创建库存记录，商品ID: {}", request.getProductId());

        String warehouseId = request.getWarehouseId() != null ? request.getWarehouseId() : "warehouse_main";

        if (stockRepository.findByProductIdAndWarehouseId(request.getProductId(), warehouseId).isPresent()) {
            throw BusinessException.of("库存记录已存在: " + request.getProductId());
        }

        Stock stock = new Stock();
        stock.setProductId(request.getProductId());
        stock.setProductName(request.getProductName());
        stock.setSkuId(request.getSkuId());
        stock.setWarehouseId(warehouseId);
        stock.setLocationId(request.getLocationId());
        stock.setUnit(request.getUnit() != null ? request.getUnit() : "件");
        stock.setCostPrice(request.getCostPrice());
        stock.setCurrentQuantity(0);
        stock.setAvailableQuantity(0);
        stock.setLockedQuantity(0);
        stock.setWarningThreshold(request.getWarningThreshold() != null ? request.getWarningThreshold() : 10);
        stock.setOverstockThreshold(request.getOverstockThreshold() != null ? request.getOverstockThreshold() : 500);

        Stock saved = stockRepository.save(stock);
        logger.info("库存记录创建成功，stockId: {}", saved.getStockId());
        return saved;
    }

    @Transactional(rollbackFor = Exception.class)
    public Stock updateStock(String stockId, StockUpdateRequest request) {
        logger.info("更新库存记录，stockId: {}", stockId);

        Stock stock = stockRepository.findById(stockId)
                .orElseThrow(() -> BusinessException.of("库存记录不存在: " + stockId));

        if (request.getProductName() != null) {
            stock.setProductName(request.getProductName());
        }
        if (request.getSkuId() != null) {
            stock.setSkuId(request.getSkuId());
        }
        if (request.getLocationId() != null) {
            stock.setLocationId(request.getLocationId());
        }
        if (request.getUnit() != null) {
            stock.setUnit(request.getUnit());
        }
        if (request.getCostPrice() != null) {
            stock.setCostPrice(request.getCostPrice());
        }
        if (request.getWarningThreshold() != null) {
            stock.setWarningThreshold(request.getWarningThreshold());
        }
        if (request.getOverstockThreshold() != null) {
            stock.setOverstockThreshold(request.getOverstockThreshold());
        }

        return stockRepository.save(stock);
    }

    public Stock getStockById(String stockId) {
        return stockRepository.findById(stockId)
                .orElseThrow(() -> BusinessException.of("库存记录不存在: " + stockId));
    }

    public Stock getStockByProductId(String productId) {
        return stockRepository.findByProductId(productId)
                .orElseThrow(() -> BusinessException.of("库存记录不存在: " + productId));
    }

    public Stock getStockByProductIdAndWarehouse(String productId, String warehouseId) {
        String whId = warehouseId != null ? warehouseId : "warehouse_main";
        return stockRepository.findByProductIdAndWarehouseId(productId, whId)
                .orElseThrow(() -> BusinessException.of("库存记录不存在: " + productId));
    }

    public List<Stock> getStocksByWarehouse(String warehouseId) {
        return stockRepository.findByWarehouseId(warehouseId);
    }

    public List<Stock> getStocksByLocation(String locationId) {
        return stockRepository.findByLocationId(locationId);
    }

    public Page<Stock> getStockPage(String productId, String warehouseId, int pageNum, int pageSize) {
        Pageable pageable = PageRequest.of(pageNum - 1, pageSize, Sort.by(Sort.Direction.DESC, "updatedAt"));
        org.springframework.data.jpa.domain.Specification<Stock> spec = null;

        if (productId != null && !productId.isEmpty()) {
            spec = (root, query, cb) -> cb.like(root.get("productId"), "%" + productId + "%");
        }
        if (warehouseId != null && !warehouseId.isEmpty()) {
            org.springframework.data.jpa.domain.Specification<Stock> warehouseSpec = 
                    (root, query, cb) -> cb.equal(root.get("warehouseId"), warehouseId);
            spec = spec != null ? spec.and(warehouseSpec) : warehouseSpec;
        }

        if (spec != null) {
            return stockRepository.findAll(spec, pageable);
        }
        return stockRepository.findAll(pageable);
    }

    public List<Stock> getLowStock() {
        return stockRepository.findLowStock();
    }

    public List<Stock> getOverstock() {
        return stockRepository.findOverstock();
    }

    @Transactional(rollbackFor = Exception.class)
    public Stock adjustQuantity(String stockId, Integer quantity, String operator, String referenceNo, String remark) {
        logger.info("调整库存数量，stockId: {}, 调整数量: {}", stockId, quantity);

        Stock stock = stockRepository.findByIdWithLock(stockId)
                .orElseThrow(() -> BusinessException.of("库存记录不存在: " + stockId));

        int beforeQuantity = stock.getCurrentQuantity();
        int newQuantity = beforeQuantity + quantity;

        if (newQuantity < 0) {
            throw BusinessException.of("库存数量不足");
        }

        stock.setCurrentQuantity(newQuantity);
        stock.setAvailableQuantity(stock.getAvailableQuantity() + quantity);
        stockRepository.save(stock);

        historyService.recordHistory(stock, OperationType.ADJUST, quantity,
                beforeQuantity, newQuantity, operator, referenceNo, remark);

        return stock;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteStock(String stockId) {
        logger.info("删除库存记录，stockId: {}", stockId);

        Stock stock = stockRepository.findById(stockId)
                .orElseThrow(() -> BusinessException.of("库存记录不存在: " + stockId));

        if (stock.getCurrentQuantity() > 0 || stock.getLockedQuantity() > 0) {
            throw BusinessException.of("库存还有数量或被锁定，无法删除");
        }

        stockRepository.delete(stock);
    }
}
