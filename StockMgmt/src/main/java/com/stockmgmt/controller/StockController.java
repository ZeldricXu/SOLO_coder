package com.stockmgmt.controller;

import com.stockmgmt.common.PageResult;
import com.stockmgmt.common.Result;
import com.stockmgmt.dto.StockCreateRequest;
import com.stockmgmt.dto.StockUpdateRequest;
import com.stockmgmt.entity.Stock;
import com.stockmgmt.entity.StockHistory;
import com.stockmgmt.service.HistoryService;
import com.stockmgmt.service.StockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/stock")
public class StockController {

    private static final Logger logger = LoggerFactory.getLogger(StockController.class);

    @Autowired
    private StockService stockService;

    @Autowired
    private HistoryService historyService;

    @PostMapping
    public Result<Stock> createStock(@Valid @RequestBody StockCreateRequest request) {
        logger.info("创建库存记录，商品ID: {}", request.getProductId());
        Stock stock = stockService.createStock(request);
        return Result.success(stock);
    }

    @PutMapping("/{stockId}")
    public Result<Stock> updateStock(@PathVariable String stockId, @RequestBody StockUpdateRequest request) {
        logger.info("更新库存记录，stockId: {}", stockId);
        Stock stock = stockService.updateStock(stockId, request);
        return Result.success(stock);
    }

    @GetMapping("/{stockId}")
    public Result<Stock> getStockById(@PathVariable String stockId) {
        logger.info("查询库存记录，stockId: {}", stockId);
        Stock stock = stockService.getStockById(stockId);
        return Result.success(stock);
    }

    @GetMapping("/product/{productId}")
    public Result<Stock> getStockByProductId(@PathVariable String productId,
                                             @RequestParam(required = false) String warehouseId) {
        logger.info("根据商品ID查询库存，productId: {}", productId);
        Stock stock = stockService.getStockByProductIdAndWarehouse(productId, warehouseId);
        return Result.success(stock);
    }

    @GetMapping
    public Result<PageResult<Stock>> getStockPage(
            @RequestParam(required = false) String productId,
            @RequestParam(required = false) String warehouseId,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        logger.info("分页查询库存列表，productId: {}, pageNum: {}, pageSize: {}", productId, pageNum, pageSize);
        Page<Stock> page = stockService.getStockPage(productId, warehouseId, pageNum, pageSize);
        PageResult<Stock> result = PageResult.of(page.getContent(), page.getTotalElements(), pageNum, pageSize);
        return Result.success(result);
    }

    @DeleteMapping("/{stockId}")
    public Result<Void> deleteStock(@PathVariable String stockId) {
        logger.info("删除库存记录，stockId: {}", stockId);
        stockService.deleteStock(stockId);
        return Result.success();
    }

    @PostMapping("/{stockId}/adjust")
    public Result<Stock> adjustQuantity(
            @PathVariable String stockId,
            @RequestParam Integer quantity,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String referenceNo,
            @RequestParam(required = false) String remark) {
        logger.info("调整库存数量，stockId: {}, quantity: {}", stockId, quantity);
        Stock stock = stockService.adjustQuantity(stockId, quantity, operator, referenceNo, remark);
        return Result.success(stock);
    }

    @GetMapping("/{stockId}/history")
    public Result<List<StockHistory>> getStockHistory(@PathVariable String stockId) {
        logger.info("查询库存历史，stockId: {}", stockId);
        List<StockHistory> history = historyService.getHistoryByStockId(stockId);
        return Result.success(history);
    }

    @GetMapping("/low")
    public Result<List<Stock>> getLowStock() {
        logger.info("查询库存不足列表");
        List<Stock> stocks = stockService.getLowStock();
        return Result.success(stocks);
    }

    @GetMapping("/overstock")
    public Result<List<Stock>> getOverstock() {
        logger.info("查询库存积压列表");
        List<Stock> stocks = stockService.getOverstock();
        return Result.success(stocks);
    }
}
