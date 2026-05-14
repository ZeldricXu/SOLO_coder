package com.restaurant.mgmt.controller;

import com.restaurant.mgmt.dto.ApiResponse;
import com.restaurant.mgmt.dto.CreateOrderRequest;
import com.restaurant.mgmt.dto.CreateOrderResponse;
import com.restaurant.mgmt.model.Order;
import com.restaurant.mgmt.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    @Autowired
    private OrderService orderService;

    @PostMapping("/create")
    public ApiResponse<CreateOrderResponse> createOrder(@RequestBody CreateOrderRequest request) {
        CreateOrderResponse response = orderService.createOrder(request);
        return ApiResponse.success(response);
    }

    @PostMapping("/{orderId}/pay")
    public ApiResponse<Order> processPayment(
            @PathVariable String orderId,
            @RequestParam(required = false, defaultValue = "wechat") String paymentMethod) {
        Order order = orderService.processPayment(orderId, paymentMethod);
        return ApiResponse.success(order);
    }

    @GetMapping("/{orderId}")
    public ApiResponse<Order> getOrder(@PathVariable String orderId) {
        Order order = orderService.getOrderById(orderId);
        return ApiResponse.success(order);
    }

    @GetMapping
    public ApiResponse<List<Order>> getAllOrders() {
        List<Order> orders = orderService.getAllOrders();
        return ApiResponse.success(orders);
    }

    @GetMapping("/status/{status}")
    public ApiResponse<List<Order>> getOrdersByStatus(@PathVariable String status) {
        List<Order> orders = orderService.getOrdersByStatus(status);
        return ApiResponse.success(orders);
    }

    @GetMapping("/range")
    public ApiResponse<List<Order>> getOrdersByTimeRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        List<Order> orders = orderService.getOrdersByTimeRange(startTime, endTime);
        return ApiResponse.success(orders);
    }

    @PostMapping("/{orderId}/cancel")
    public ApiResponse<Order> cancelOrder(
            @PathVariable String orderId,
            @RequestParam(required = false) String reason) {
        Order order = orderService.cancelOrder(orderId, reason);
        return ApiResponse.success(order);
    }

    @PostMapping("/{orderId}/complete")
    public ApiResponse<Order> completeOrder(@PathVariable String orderId) {
        Order order = orderService.completeOrder(orderId);
        return ApiResponse.success(order);
    }

    @PostMapping("/{orderId}/pay-test")
    public ApiResponse<Map<String, Object>> testPaymentFlow(@RequestBody CreateOrderRequest request) {
        CreateOrderResponse createResponse = orderService.createOrder(request);
        
        Order paidOrder = orderService.processPayment(createResponse.getOrderId(), "wechat");
        
        Map<String, Object> result = new HashMap<>();
        result.put("orderId", paidOrder.getOrderId());
        result.put("status", paidOrder.getOrderStatus());
        result.put("orderAmount", paidOrder.getOrderAmount());
        result.put("paymentMethod", paidOrder.getPaymentMethod());
        result.put("paidAt", paidOrder.getPaidAt());
        
        return ApiResponse.success(result);
    }
}
