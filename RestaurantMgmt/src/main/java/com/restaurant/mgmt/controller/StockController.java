package com.restaurant.mgmt.controller;

import com.restaurant.mgmt.dto.ApiResponse;
import com.restaurant.mgmt.dto.QueryStockResponse;
import com.restaurant.mgmt.model.Stock;
import com.restaurant.mgmt.model.StockMovement;
import com.restaurant.mgmt.model.StockWarning;
import com.restaurant.mgmt.service.StockService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/stocks")
public class StockController {

    @Autowired
    private StockService stockService;

    @PostMapping
    public ApiResponse<Stock> createStock(@RequestBody Stock stock) {
        Stock saved = stockService.createStock(stock);
        return ApiResponse.success(saved);
    }

    @GetMapping
    public ApiResponse<List<Stock>> getAllStocks() {
        List<Stock> stocks = stockService.getAllStocks();
        return ApiResponse.success(stocks);
    }

    @GetMapping("/{stockId}")
    public ApiResponse<Stock> getStock(@PathVariable String stockId) {
        Stock stock = stockService.getStockById(stockId);
        return ApiResponse.success(stock);
    }

    @GetMapping("/query")
    public ApiResponse<QueryStockResponse> queryStock(@RequestParam String ingredientId) {
        Stock stock = stockService.getStockByIngredientId(ingredientId);
        QueryStockResponse response = new QueryStockResponse(
            stock.getStockQuantity(),
            stock.getStockStatus()
        );
        return ApiResponse.success(response);
    }

    @GetMapping("/ingredient/{ingredientId}")
    public ApiResponse<Stock> getStockByIngredient(@PathVariable String ingredientId) {
        Stock stock = stockService.getStockByIngredientId(ingredientId);
        return ApiResponse.success(stock);
    }

    @GetMapping("/low-stock")
    public ApiResponse<List<Stock>> getLowStockItems() {
        List<Stock> stocks = stockService.getLowStockItems();
        return ApiResponse.success(stocks);
    }

    @GetMapping("/category/{category}")
    public ApiResponse<List<Stock>> getStocksByCategory(@PathVariable String category) {
        List<Stock> stocks = stockService.getStocksByCategory(category);
        return ApiResponse.success(stocks);
    }

    @PostMapping("/{ingredientId}/add")
    public ApiResponse<Stock> addStock(
            @PathVariable String ingredientId,
            @RequestParam double quantity,
            @RequestParam(required = false, defaultValue = "system") String operator,
            @RequestParam(required = false) String remark) {
        Stock stock = stockService.addStock(ingredientId, quantity, operator, remark, null);
        return ApiResponse.success(stock);
    }

    @PostMapping("/{ingredientId}/reduce")
    public ApiResponse<Stock> reduceStock(
            @PathVariable String ingredientId,
            @RequestParam double quantity,
            @RequestParam(required = false, defaultValue = "system") String operator,
            @RequestParam(required = false) String remark) {
        Stock stock = stockService.reduceStock(ingredientId, quantity, operator, remark, null);
        return ApiResponse.success(stock);
    }

    @PostMapping("/batch-check")
    public ApiResponse<Map<String, Boolean>> batchCheckAndReduce(
            @RequestBody Map<String, Double> ingredientQuantities,
            @RequestParam(required = false, defaultValue = "system") String operator) {
        Map<String, Boolean> result = stockService.checkAndReduceStocks(ingredientQuantities, operator, null);
        return ApiResponse.success(result);
    }

    @PutMapping("/{stockId}")
    public ApiResponse<Stock> updateStock(
            @PathVariable String stockId,
            @RequestBody Stock stock) {
        Stock updated = stockService.updateStock(stockId, stock);
        return ApiResponse.success(updated);
    }

    @DeleteMapping("/{stockId}")
    public ApiResponse<Void> deleteStock(@PathVariable String stockId) {
        stockService.deleteStock(stockId);
        return ApiResponse.success(null);
    }

    @GetMapping("/warnings")
    public ApiResponse<List<StockWarning>> getAllWarnings() {
        List<StockWarning> warnings = stockService.getAllWarnings();
        return ApiResponse.success(warnings);
    }

    @GetMapping("/warnings/unhandled")
    public ApiResponse<List<StockWarning>> getUnhandledWarnings() {
        List<StockWarning> warnings = stockService.getUnhandledWarnings();
        return ApiResponse.success(warnings);
    }

    @PostMapping("/warnings/{warningId}/handle")
    public ApiResponse<StockWarning> handleWarning(
            @PathVariable String warningId,
            @RequestParam String handleNote,
            @RequestParam(required = false, defaultValue = "system") String operator) {
        StockWarning warning = stockService.handleWarning(warningId, handleNote, operator);
        return ApiResponse.success(warning);
    }

    @GetMapping("/movements/{ingredientId}")
    public ApiResponse<List<StockMovement>> getMovementsByIngredient(@PathVariable String ingredientId) {
        List<StockMovement> movements = stockService.getMovementsByIngredient(ingredientId);
        return ApiResponse.success(movements);
    }

    @GetMapping("/movements/range")
    public ApiResponse<List<StockMovement>> getMovementsByTimeRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        List<StockMovement> movements = stockService.getMovementsByTimeRange(startTime, endTime);
        return ApiResponse.success(movements);
    }

    @GetMapping("/ingredient/{ingredientId}/summary")
    public ApiResponse<Map<String, Object>> getStockSummary(@PathVariable String ingredientId) {
        Stock stock = stockService.getStockByIngredientId(ingredientId);
        List<StockMovement> movements = stockService.getMovementsByIngredient(ingredientId);
        
        Map<String, Object> summary = new HashMap<>();
        summary.put("stock", stock);
        summary.put("movementCount", movements.size());
        summary.put("status", stock.getStockStatus());
        summary.put("isLowStock", stock.isLowStock());
        
        return ApiResponse.success(summary);
    }
}
