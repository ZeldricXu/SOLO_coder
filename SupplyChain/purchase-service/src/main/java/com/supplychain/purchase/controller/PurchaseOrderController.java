package com.supplychain.purchase.controller;

import com.supplychain.common.dto.OrderCreateRequest;
import com.supplychain.common.dto.ResponseResult;
import com.supplychain.common.entity.PurchaseOrder;
import com.supplychain.purchase.service.PurchaseOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "采购订单管理", description = "采购订单创建与审批管理接口")
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class PurchaseOrderController {

    private final PurchaseOrderService orderService;

    @Operation(summary = "创建采购订单")
    @PostMapping("/create")
    public ResponseResult<Map<String, Object>> createOrder(@RequestBody OrderCreateRequest request) {
        PurchaseOrder order = orderService.createOrder(request);
        return ResponseResult.success(Map.of(
                "order_id", order.getOrderId(),
                "status", order.getOrderStatus()
        ));
    }

    @Operation(summary = "获取订单详情")
    @GetMapping("/{orderId}")
    public ResponseResult<PurchaseOrder> getOrder(@PathVariable String orderId) {
        return ResponseResult.success(orderService.getOrder(orderId));
    }

    @Operation(summary = "获取订单列表")
    @GetMapping
    public ResponseResult<List<PurchaseOrder>> listOrders(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String supplierId) {
        return ResponseResult.success(orderService.listOrders(status, supplierId));
    }

    @Operation(summary = "审批通过订单")
    @PostMapping("/{orderId}/approve")
    public ResponseResult<PurchaseOrder> approveOrder(
            @PathVariable String orderId,
            @RequestBody Map<String, String> request) {
        String approver = request.getOrDefault("approver", "system");
        return ResponseResult.success(orderService.approveOrder(orderId, approver));
    }

    @Operation(summary = "审批拒绝订单")
    @PostMapping("/{orderId}/reject")
    public ResponseResult<PurchaseOrder> rejectOrder(
            @PathVariable String orderId,
            @RequestBody Map<String, String> request) {
        String approver = request.getOrDefault("approver", "system");
        String reason = request.getOrDefault("reason", "审批拒绝");
        return ResponseResult.success(orderService.rejectOrder(orderId, approver, reason));
    }
}
