package com.supplychain.logistics.controller;

import com.supplychain.common.dto.ResponseResult;
import com.supplychain.common.entity.LogisticsTracking;
import com.supplychain.logistics.service.LogisticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "物流追踪管理", description = "物流追踪与状态管理接口")
@RestController
@RequestMapping("/api/tracking")
@RequiredArgsConstructor
public class LogisticsController {

    private final LogisticsService logisticsService;

    @Operation(summary = "创建物流追踪")
    @PostMapping
    public ResponseResult<LogisticsTracking> createTracking(@RequestBody Map<String, String> request) {
        String orderId = request.get("order_id");
        String carrier = request.get("carrier");
        String trackingNumber = request.get("tracking_number");
        return ResponseResult.success(logisticsService.createTracking(orderId, carrier, trackingNumber));
    }

    @Operation(summary = "物流追踪查询")
    @GetMapping("/query")
    public ResponseResult<Map<String, Object>> queryTracking(@RequestParam String orderId) {
        return ResponseResult.success(logisticsService.queryTrackingInfo(orderId));
    }

    @Operation(summary = "获取追踪详情")
    @GetMapping("/{trackingId}")
    public ResponseResult<LogisticsTracking> getTracking(@PathVariable String trackingId) {
        return ResponseResult.success(logisticsService.getTracking(trackingId));
    }

    @Operation(summary = "获取订单物流")
    @GetMapping("/order/{orderId}")
    public ResponseResult<LogisticsTracking> getTrackingByOrder(@PathVariable String orderId) {
        return ResponseResult.success(logisticsService.getTrackingByOrder(orderId));
    }

    @Operation(summary = "更新物流状态")
    @PostMapping("/order/{orderId}/update")
    public ResponseResult<LogisticsTracking> updateTrackingStatus(
            @PathVariable String orderId,
            @RequestBody Map<String, String> request) {
        String status = request.get("status");
        String location = request.getOrDefault("location", "");
        String description = request.get("description");
        return ResponseResult.success(logisticsService.updateTrackingStatus(orderId, status, location, description));
    }

    @Operation(summary = "模拟物流进度")
    @PostMapping("/order/{orderId}/simulate")
    public ResponseResult<LogisticsTracking> simulateTracking(@PathVariable String orderId) {
        return ResponseResult.success(logisticsService.simulateTracking(orderId));
    }

    @Operation(summary = "获取追踪列表")
    @GetMapping
    public ResponseResult<List<LogisticsTracking>> listTrackings(
            @RequestParam(required = false) String orderId,
            @RequestParam(required = false) String status) {
        return ResponseResult.success(logisticsService.listTrackings(orderId, status));
    }
}
