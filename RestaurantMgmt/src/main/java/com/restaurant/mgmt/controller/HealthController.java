package com.restaurant.mgmt.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "RestaurantMgmt");
        response.put("version", "1.0.0");
        response.put("timestamp", LocalDateTime.now().toString());
        
        Map<String, String> modules = new HashMap<>();
        modules.put("dish", "active");
        modules.put("order", "active");
        modules.put("table", "active");
        modules.put("stock", "active");
        modules.put("analysis", "active");
        modules.put("employee", "active");
        modules.put("review", "active");
        modules.put("promotion", "active");
        modules.put("history", "active");
        response.put("modules", modules);
        
        return response;
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> response = new HashMap<>();
        response.put("name", "RestaurantMgmt 餐饮门店管理服务");
        response.put("description", "支持菜品管理、订单处理、座位管理、库存管理以及营业数据分析功能");
        response.put("version", "1.0.0");
        response.put("javaVersion", System.getProperty("java.version"));
        
        Map<String, String> apis = new HashMap<>();
        apis.put("订单创建", "POST /api/v1/orders/create");
        apis.put("订单支付", "POST /api/v1/orders/{orderId}/pay");
        apis.put("座位预约", "POST /api/v1/tables/reserve");
        apis.put("库存查询", "GET /api/v1/stocks/query");
        apis.put("菜品列表", "GET /api/v1/dishes");
        apis.put("营业统计", "GET /api/v1/analysis/summary");
        response.put("keyApis", apis);
        
        return response;
    }
}
