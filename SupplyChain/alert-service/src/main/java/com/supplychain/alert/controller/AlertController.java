package com.supplychain.alert.controller;

import com.supplychain.common.dto.ResponseResult;
import com.supplychain.common.entity.InventoryWarning;
import com.supplychain.alert.service.AlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "预警管理", description = "供应链预警检测管理接口")
@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
public class AlertController {

    private final AlertService alertService;

    @Operation(summary = "创建预警")
    @PostMapping
    public ResponseResult<InventoryWarning> createWarning(@RequestBody InventoryWarning warning) {
        return ResponseResult.success(alertService.createWarning(warning));
    }

    @Operation(summary = "创建低库存预警")
    @PostMapping("/low-stock")
    public ResponseResult<InventoryWarning> createLowStockWarning(@RequestBody Map<String, Integer> request) {
        String itemId = request.get("itemId").toString();
        int currentQty = request.getOrDefault("currentQty", 0);
        int threshold = request.getOrDefault("threshold", 50);
        return ResponseResult.success(alertService.createLowStockWarning(itemId, currentQty, threshold));
    }

    @Operation(summary = "获取活跃预警")
    @GetMapping("/active")
    public ResponseResult<List<InventoryWarning>> getActiveWarnings() {
        return ResponseResult.success(alertService.getActiveWarnings());
    }

    @Operation(summary = "获取预警列表")
    @GetMapping
    public ResponseResult<List<InventoryWarning>> listWarnings(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String level) {
        return ResponseResult.success(alertService.listWarnings(status, type, level));
    }

    @Operation(summary = "按类型获取预警")
    @GetMapping("/type/{type}")
    public ResponseResult<List<InventoryWarning>> getWarningsByType(@PathVariable String type) {
        return ResponseResult.success(alertService.getWarningsByType(type));
    }

    @Operation(summary = "按级别获取预警")
    @GetMapping("/level/{level}")
    public ResponseResult<List<InventoryWarning>> getWarningsByLevel(@PathVariable String level) {
        return ResponseResult.success(alertService.getWarningsByLevel(level));
    }

    @Operation(summary = "处理预警")
    @PostMapping("/{warningId}/handle")
    public ResponseResult<InventoryWarning> handleWarning(
            @PathVariable String warningId,
            @RequestBody Map<String, String> request) {
        String handler = request.getOrDefault("handler", "system");
        String note = request.get("note");
        return ResponseResult.success(alertService.handleWarning(warningId, handler, note));
    }

    @Operation(summary = "获取预警统计")
    @GetMapping("/stats")
    public ResponseResult<Map<String, Object>> getWarningStats() {
        return ResponseResult.success(alertService.getWarningStats());
    }
}
