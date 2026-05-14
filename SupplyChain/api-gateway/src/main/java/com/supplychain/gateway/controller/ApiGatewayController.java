package com.supplychain.gateway.controller;

import com.supplychain.common.dto.InventorySyncRequest;
import com.supplychain.common.dto.OrderCreateRequest;
import com.supplychain.common.dto.ResponseResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Slf4j
@Tag(name = "API网关", description = "统一API接口 - 采购创建、库存同步、物流查询")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ApiGatewayController {

    private final RestTemplate restTemplate;

    @Value("${services.purchase.base-url}")
    private String purchaseServiceUrl;

    @Value("${services.inventory.base-url}")
    private String inventoryServiceUrl;

    @Value("${services.logistics.base-url}")
    private String logisticsServiceUrl;

    @Operation(summary = "采购订单创建API")
    @PostMapping("/orders/create")
    public ResponseResult<Map<String, Object>> createOrder(@RequestBody OrderCreateRequest request) {
        log.info("调用采购订单创建API: supplierId={}", request.getSupplierId());
        String url = purchaseServiceUrl + "/api/orders/create";
        ResponseEntity<ResponseResult> response = restTemplate.postForEntity(url, request, ResponseResult.class);
        return response.getBody();
    }

    @Operation(summary = "库存同步API")
    @PostMapping("/inventory/sync")
    public ResponseResult<Map<String, Object>> syncInventory(@RequestBody InventorySyncRequest request) {
        log.info("调用库存同步API: supplierId={}", request.getSupplierId());
        String url = inventoryServiceUrl + "/api/inventory/sync";
        ResponseEntity<ResponseResult> response = restTemplate.postForEntity(url, request, ResponseResult.class);
        return response.getBody();
    }

    @Operation(summary = "物流追踪查询API")
    @GetMapping("/tracking/query")
    public ResponseResult<Map<String, Object>> queryTracking(@RequestParam String orderId) {
        log.info("调用物流追踪查询API: orderId={}", orderId);
        String url = logisticsServiceUrl + "/api/tracking/query?orderId=" + orderId;
        ResponseEntity<ResponseResult> response = restTemplate.getForEntity(url, ResponseResult.class);
        return response.getBody();
    }

    @Operation(summary = "API网关健康检查")
    @GetMapping("/health")
    public ResponseResult<Map<String, Object>> health() {
        return ResponseResult.success(Map.of(
            "status", "UP",
            "service", "api-gateway",
            "timestamp", System.currentTimeMillis()
        ));
    }
}
