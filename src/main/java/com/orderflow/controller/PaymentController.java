package com.orderflow.controller;

import com.orderflow.common.Result;
import com.orderflow.entity.Payment;
import com.orderflow.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private static final Logger logger = LoggerFactory.getLogger(PaymentController.class);

    @Autowired
    private PaymentService paymentService;

    @GetMapping("/{paymentId}")
    public Result<Payment> getPayment(@PathVariable String paymentId) {
        logger.info("查询支付记录，支付ID: {}", paymentId);
        Payment payment = paymentService.getPayment(paymentId);
        return Result.success(payment);
    }

    @PostMapping("/callback")
    public Result<Payment> handlePaymentCallback(@RequestBody Map<String, Object> callbackData) {
        logger.info("处理支付回调，数据: {}", callbackData);

        String transactionId = (String) callbackData.get("transactionId");
        Boolean success = (Boolean) callbackData.get("success");
        String failReason = (String) callbackData.get("failReason");

        if (success == null) {
            success = true;
        }

        Payment payment = paymentService.handlePaymentCallback(transactionId, success, failReason);
        return Result.success(payment);
    }

    @GetMapping("/order/{orderId}")
    public Result<Payment> getLastPaymentByOrderId(@PathVariable String orderId) {
        logger.info("查询订单最后支付记录，订单ID: {}", orderId);
        Payment payment = paymentService.getLastPaymentByOrderId(orderId);
        if (payment == null) {
            return Result.error("该订单暂无支付记录");
        }
        return Result.success(payment);
    }
}
