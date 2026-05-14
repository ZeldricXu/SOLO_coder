package com.stockmgmt.controller;

import com.stockmgmt.common.Result;
import com.stockmgmt.dto.CheckCreateRequest;
import com.stockmgmt.dto.CheckDiffRequest;
import com.stockmgmt.entity.StockCheck;
import com.stockmgmt.entity.StockCheckDiff;
import com.stockmgmt.service.CheckService;
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
@RequestMapping("/api/v1/stock/checks")
public class CheckController {

    private static final Logger logger = LoggerFactory.getLogger(CheckController.class);

    @Autowired
    private CheckService checkService;

    @PostMapping
    public Result<StockCheck> createCheck(@Valid @RequestBody CheckCreateRequest request) {
        logger.info("创建盘点任务，仓库: {}", request.getWarehouseId());
        StockCheck check = checkService.createCheck(request);
        return Result.success(check);
    }

    @PostMapping("/{checkId}/start")
    public Result<StockCheck> startCheck(
            @PathVariable String checkId,
            @RequestParam(required = false) String operator) {
        logger.info("开始盘点任务，checkId: {}", checkId);
        StockCheck check = checkService.startCheck(checkId, operator);
        return Result.success(check);
    }

    @PostMapping("/{checkId}/complete")
    public Result<StockCheck> completeCheck(
            @PathVariable String checkId,
            @RequestParam(required = false) String operator) {
        logger.info("完成盘点任务，checkId: {}", checkId);
        StockCheck check = checkService.completeCheck(checkId, operator);
        return Result.success(check);
    }

    @PostMapping("/{checkId}/cancel")
    public Result<StockCheck> cancelCheck(
            @PathVariable String checkId,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String remark) {
        logger.info("取消盘点任务，checkId: {}", checkId);
        StockCheck check = checkService.cancelCheck(checkId, operator, remark);
        return Result.success(check);
    }

    @GetMapping("/{checkId}")
    public Result<StockCheck> getCheckById(@PathVariable String checkId) {
        logger.info("查询盘点任务详情，checkId: {}", checkId);
        StockCheck check = checkService.getCheckById(checkId);
        return Result.success(check);
    }

    @GetMapping
    public Result<Map<String, Object>> getCheckPage(
            @RequestParam(required = false) String warehouseId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        logger.info("分页查询盘点任务，warehouseId: {}, status: {}", warehouseId, status);
        Page<StockCheck> page = checkService.getCheckPage(warehouseId, status, pageNum, pageSize);
        Map<String, Object> result = new HashMap<>();
        result.put("checks", page.getContent());
        result.put("total", page.getTotalElements());
        result.put("pageNum", pageNum);
        result.put("pageSize", pageSize);
        result.put("totalPages", page.getTotalPages());
        return Result.success(result);
    }

    @PostMapping("/diff")
    public Result<StockCheckDiff> recordCheckDiff(@Valid @RequestBody CheckDiffRequest request) {
        logger.info("记录盘点差异，checkId: {}", request.getCheckId());
        StockCheckDiff diff = checkService.recordCheckDiff(request);
        return Result.success(diff);
    }

    @GetMapping("/{checkId}/diffs")
    public Result<List<StockCheckDiff>> getCheckDiffs(@PathVariable String checkId) {
        logger.info("查询盘点差异列表，checkId: {}", checkId);
        List<StockCheckDiff> diffs = checkService.getDiffsByCheckId(checkId);
        return Result.success(diffs);
    }

    @PostMapping("/diffs/{diffId}/approve")
    public Result<StockCheckDiff> approveDiff(
            @PathVariable String diffId,
            @RequestParam(required = false) String operator) {
        logger.info("审批盘点差异，diffId: {}", diffId);
        StockCheckDiff diff = checkService.approveDiff(diffId, operator);
        return Result.success(diff);
    }

    @PostMapping("/diffs/{diffId}/reject")
    public Result<StockCheckDiff> rejectDiff(
            @PathVariable String diffId,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String remark) {
        logger.info("拒绝盘点差异，diffId: {}", diffId);
        StockCheckDiff diff = checkService.rejectDiff(diffId, operator, remark);
        return Result.success(diff);
    }

    @PostMapping("/diffs/{diffId}/process")
    public Result<StockCheckDiff> processDiff(
            @PathVariable String diffId,
            @RequestParam(required = false) String operator) {
        logger.info("处理盘点差异，diffId: {}", diffId);
        StockCheckDiff diff = checkService.processDiff(diffId, operator);
        return Result.success(diff);
    }
}
