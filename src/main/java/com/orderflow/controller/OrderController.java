package com.orderflow.controller;

import com.orderflow.common.PageResult;
import com.orderflow.common.Result;
import com.orderflow.dto.*;
import com.orderflow.entity.*;
import com.orderflow.payment.PaymentAsyncService;
import com.orderflow.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private static final Logger logger = LoggerFactory.getLogger(OrderController.class);

    @Autowired
    private OrderCreationService orderCreationService;

    @Autowired
    private OrderQueryService orderQueryService;

    @Autowired
    private OrderStatusService orderStatusService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentAsyncService paymentAsyncService;

    @Autowired
    private ShippingService shippingService;

    @Autowired
    private RefundService refundService;

    @Autowired
    private StatisticsService statisticsService;

    @PostMapping("/create")
    public Result<OrderCreateResponse> createOrder(@Valid @RequestBody OrderCreateRequest request) {
        logger.info("创建订单请求，用户ID: {}", request.getUserId());
        OrderCreateResponse response = orderCreationService.createOrder(request);
        return Result.success(response);
    }

    @PostMapping("/pay")
    public Result<OrderPayResponse> payOrder(@Valid @RequestBody OrderPayRequest request) {
        logger.info("订单支付请求（同步），订单ID: {}", request.getOrderId());
        OrderPayResponse response = paymentService.processPayment(request);
        return Result.success(response);
    }

    @PostMapping("/pay-async")
    public Result<OrderPayResponse> payOrderAsync(@Valid @RequestBody OrderPayRequest request) {
        logger.info("订单支付请求（异步），订单ID: {}", request.getOrderId());
        OrderPayResponse response = paymentService.initiateAsyncPayment(request);
        return Result.success(response);
    }

    @GetMapping("/pay-result/{paymentId}")
    public Result<Map<String, Object>> getPaymentResult(@PathVariable String paymentId) {
        logger.info("查询支付结果，支付ID: {}", paymentId);
        PaymentAsyncService.PaymentResult result = paymentAsyncService.getPaymentResult(paymentId);

        Map<String, Object> response = new HashMap<>();
        response.put("paymentId", paymentId);
        response.put("success", result.isSuccess());
        response.put("transactionId", result.getTransactionId());
        response.put("failReason", result.getFailReason());

        return Result.success(response);
    }

    @GetMapping("/query")
    public Result<PageResult<Order>> queryOrders(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        logger.info("订单查询请求，用户ID: {}, 状态: {}, 页码: {}, 页大小: {}", userId, status, pageNum, pageSize);

        OrderQueryRequest request = new OrderQueryRequest();
        request.setUserId(userId);
        request.setStatus(status);
        request.setPageNum(pageNum);
        request.setPageSize(pageSize);

        PageResult<Order> result = orderQueryService.queryOrders(request);
        return Result.success(result);
    }

    @GetMapping("/{orderId}")
    public Result<Map<String, Object>> getOrderDetail(@PathVariable String orderId) {
        logger.info("查询订单详情，订单ID: {}", orderId);

        Order order = orderQueryService.getOrderDetail(orderId);
        List<OrderItem> items = orderQueryService.getOrderItems(orderId);
        List<OrderStatusLog> statusHistory = orderQueryService.getOrderStatusHistory(orderId);
        List<Payment> payments = orderQueryService.getOrderPayments(orderId);

        Map<String, Object> result = new HashMap<>();
        result.put("order", order);
        result.put("items", items);
        result.put("statusHistory", statusHistory);
        result.put("payments", payments);

        return Result.success(result);
    }

    @GetMapping("/by-order-no/{orderNo}")
    public Result<Order> getOrderByOrderNo(@PathVariable String orderNo) {
        logger.info("根据订单号查询，订单号: {}", orderNo);
        Order order = orderQueryService.getOrderByOrderNo(orderNo);
        return Result.success(order);
    }

    @PostMapping("/ship")
    public Result<Shipping> shipOrder(@Valid @RequestBody OrderShipRequest request) {
        logger.info("订单发货请求，订单ID: {}", request.getOrderId());
        Shipping shipping = shippingService.shipOrder(request);
        return Result.success(shipping);
    }

    @PostMapping("/{orderId}/confirm-delivery")
    public Result<Shipping> confirmDelivery(@PathVariable String orderId) {
        logger.info("确认收货，订单ID: {}", orderId);
        Shipping shipping = shippingService.confirmDelivery(orderId);
        return Result.success(shipping);
    }

    @GetMapping("/{orderId}/shipping")
    public Result<Shipping> getShipping(@PathVariable String orderId) {
        logger.info("查询发货信息，订单ID: {}", orderId);
        Shipping shipping = shippingService.getShippingByOrderId(orderId);
        return Result.success(shipping);
    }

    @PostMapping("/refund/apply")
    public Result<Refund> applyRefund(@Valid @RequestBody RefundApplyRequest request) {
        logger.info("申请退款，订单ID: {}", request.getOrderId());
        Refund refund = refundService.applyRefund(request);
        return Result.success(refund);
    }

    @PostMapping("/refund/{refundId}/approve")
    public Result<Refund> approveRefund(@PathVariable String refundId) {
        logger.info("审批通过退款，退款ID: {}", refundId);
        Refund refund = refundService.approveRefund(refundId);
        return Result.success(refund);
    }

    @PostMapping("/refund/{refundId}/reject")
    public Result<Refund> rejectRefund(@PathVariable String refundId,
                                        @RequestParam(required = false) String rejectReason) {
        logger.info("拒绝退款，退款ID: {}", refundId);
        Refund refund = refundService.rejectRefund(refundId, rejectReason);
        return Result.success(refund);
    }

    @GetMapping("/refund/{refundId}")
    public Result<Refund> getRefund(@PathVariable String refundId) {
        logger.info("查询退款详情，退款ID: {}", refundId);
        Refund refund = refundService.getRefund(refundId);
        return Result.success(refund);
    }

    @GetMapping("/{orderId}/refunds")
    public Result<List<Refund>> getOrderRefunds(@PathVariable String orderId) {
        logger.info("查询订单退款记录，订单ID: {}", orderId);
        List<Refund> refunds = refundService.getRefundsByOrderId(orderId);
        return Result.success(refunds);
    }

    @GetMapping("/status/{orderId}")
    public Result<List<OrderStatusLog>> getOrderStatusHistory(@PathVariable String orderId) {
        logger.info("查询订单状态历史，订单ID: {}", orderId);
        List<OrderStatusLog> logs = orderStatusService.getStatusHistory(orderId);
        return Result.success(logs);
    }

    @GetMapping("/statistics")
    public Result<Map<String, Object>> getStatistics() {
        logger.info("获取订单统计数据");
        Map<String, Object> statistics = statisticsService.getOrderStatistics();
        return Result.success(statistics);
    }

    @GetMapping("/recent")
    public Result<List<Map<String, Object>>> getRecentOrders(
            @RequestParam(defaultValue = "10") Integer limit) {
        logger.info("获取最近订单，数量: {}", limit);
        List<Map<String, Object>> orders = statisticsService.getRecentOrders(limit);
        return Result.success(orders);
    }
}
