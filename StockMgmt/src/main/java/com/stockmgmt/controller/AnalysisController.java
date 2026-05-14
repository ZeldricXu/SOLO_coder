package com.stockmgmt.controller;

import com.stockmgmt.common.Result;
import com.stockmgmt.service.AnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/stock/analysis")
public class AnalysisController {

    private static final Logger logger = LoggerFactory.getLogger(AnalysisController.class);

    @Autowired
    private AnalysisService analysisService;

    @GetMapping("/overview")
    public Result<Map<String, Object>> getOverview() {
        logger.info("获取库存概览统计");
        Map<String, Object> overview = analysisService.getOverviewStatistics();
        return Result.success(overview);
    }

    @GetMapping("/turnover")
    public Result<Map<String, Object>> getTurnoverAnalysis(
            @RequestParam(defaultValue = "30") Integer days) {
        logger.info("获取库存周转分析，天数: {}", days);
        Map<String, Object> analysis = analysisService.getTurnoverAnalysis(days);
        return Result.success(analysis);
    }

    @GetMapping("/cost")
    public Result<Map<String, Object>> getCostAnalysis() {
        logger.info("获取库存成本分析");
        Map<String, Object> analysis = analysisService.getCostAnalysis();
        return Result.success(analysis);
    }

    @GetMapping("/low-stock/top")
    public Result<List<Map<String, Object>>> getTopLowStock(
            @RequestParam(defaultValue = "10") Integer limit) {
        logger.info("获取库存不足TOP列表");
        List<Map<String, Object>> result = analysisService.getTopLowStock(limit);
        return Result.success(result);
    }

    @GetMapping("/overstock/top")
    public Result<List<Map<String, Object>>> getTopOverstock(
            @RequestParam(defaultValue = "10") Integer limit) {
        logger.info("获取库存积压TOP列表");
        List<Map<String, Object>> result = analysisService.getTopOverstock(limit);
        return Result.success(result);
    }

    @GetMapping("/warehouse/{warehouseId}")
    public Result<Map<String, Object>> getWarehouseStatistics(@PathVariable String warehouseId) {
        logger.info("获取仓库统计，warehouseId: {}", warehouseId);
        Map<String, Object> stats = analysisService.getWarehouseStatistics(warehouseId);
        return Result.success(stats);
    }

    @GetMapping("/trend/daily")
    public Result<Map<String, Object>> getDailyTrend(
            @RequestParam(defaultValue = "7") Integer days) {
        logger.info("获取每日趋势，天数: {}", days);
        Map<String, Object> trend = analysisService.getDailyTrend(days);
        return Result.success(trend);
    }
}
