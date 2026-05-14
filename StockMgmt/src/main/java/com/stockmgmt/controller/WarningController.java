package com.stockmgmt.controller;

import com.stockmgmt.common.PageResult;
import com.stockmgmt.common.Result;
import com.stockmgmt.dto.WarningHandleRequest;
import com.stockmgmt.entity.StockWarning;
import com.stockmgmt.enums.WarningType;
import com.stockmgmt.service.WarningService;
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
@RequestMapping("/api/v1/stock/warnings")
public class WarningController {

    private static final Logger logger = LoggerFactory.getLogger(WarningController.class);

    @Autowired
    private WarningService warningService;

    @GetMapping
    public Result<Map<String, Object>> getWarnings(
            @RequestParam(required = false) String warningType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        logger.info("查询预警列表，warningType: {}, status: {}", warningType, status);
        Page<StockWarning> page = warningService.getWarningPage(warningType, status, pageNum, pageSize);
        Map<String, Object> result = new HashMap<>();
        result.put("warnings", page.getContent());
        result.put("total", page.getTotalElements());
        result.put("pageNum", pageNum);
        result.put("pageSize", pageSize);
        result.put("totalPages", page.getTotalPages());
        return Result.success(result);
    }

    @GetMapping("/{warningId}")
    public Result<StockWarning> getWarningById(@PathVariable String warningId) {
        logger.info("查询预警详情，warningId: {}", warningId);
        StockWarning warning = warningService.getWarningById(warningId);
        return Result.success(warning);
    }

    @GetMapping("/active")
    public Result<Map<String, Object>> getActiveWarnings() {
        logger.info("查询活动预警");
        List<StockWarning> warnings = warningService.getActiveWarnings();
        Map<String, Object> result = new HashMap<>();
        result.put("warnings", warnings);
        result.put("count", warnings.size());
        return Result.success(result);
    }

    @GetMapping("/low-stock")
    public Result<Map<String, Object>> getLowStockWarnings() {
        logger.info("查询库存不足预警");
        List<StockWarning> warnings = warningService.getActiveWarningsByType(WarningType.LOW_STOCK);
        Map<String, Object> result = new HashMap<>();
        result.put("warnings", warnings);
        result.put("count", warnings.size());
        return Result.success(result);
    }

    @GetMapping("/overstock")
    public Result<Map<String, Object>> getOverstockWarnings() {
        logger.info("查询库存积压预警");
        List<StockWarning> warnings = warningService.getActiveWarningsByType(WarningType.OVERSTOCK);
        Map<String, Object> result = new HashMap<>();
        result.put("warnings", warnings);
        result.put("count", warnings.size());
        return Result.success(result);
    }

    @PostMapping("/{warningId}/handle")
    public Result<StockWarning> handleWarning(
            @PathVariable String warningId,
            @Valid @RequestBody WarningHandleRequest request) {
        logger.info("处理预警，warningId: {}", warningId);
        StockWarning warning = warningService.handleWarning(warningId, request);
        return Result.success(warning);
    }

    @GetMapping("/stats")
    public Result<Map<String, Object>> getWarningStats() {
        logger.info("查询预警统计");
        Map<String, Object> stats = new HashMap<>();
        stats.put("activeCount", warningService.getActiveWarningCount());
        stats.put("lowStockCount", warningService.getActiveWarningsByType(WarningType.LOW_STOCK).size());
        stats.put("overstockCount", warningService.getActiveWarningsByType(WarningType.OVERSTOCK).size());
        return Result.success(stats);
    }
}
