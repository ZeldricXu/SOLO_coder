package com.paycenter.controller;

import com.paycenter.dto.ApiResponse;
import com.paycenter.dto.PaymentRequest;
import com.paycenter.dto.PaymentResponse;
import com.paycenter.entity.Transaction;
import com.paycenter.service.TransactionService;
import com.paycenter.service.TransactionStatService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/pay")
public class PaymentController {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private TransactionStatService transactionStatService;

    @PostMapping("/request")
    public ApiResponse<PaymentResponse> createPayment(@Valid @RequestBody PaymentRequest request) {
        PaymentResponse response = transactionService.createPayment(request);
        
        transactionStatService.updateStats(request.getMerchantId(), LocalDate.now(), false);
        
        return ApiResponse.success(response);
    }

    @PostMapping("/confirm/{transactionId}")
    public ApiResponse<Transaction> confirmPayment(
            @PathVariable String transactionId,
            @RequestParam boolean success,
            @RequestBody(required = false) String notifyData) {
        Transaction transaction = transactionService.confirmPayment(transactionId, success, notifyData);
        
        if (success) {
            transactionStatService.updateStats(transaction.getMerchantId(), LocalDate.now(), true);
        }
        
        return ApiResponse.success(transaction);
    }

    @GetMapping("/transaction/{transactionId}")
    public ApiResponse<Transaction> getTransaction(@PathVariable String transactionId) {
        Optional<Transaction> transaction = transactionService.getTransactionById(transactionId);
        return transaction.map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.error(404, "交易不存在"));
    }

    @GetMapping("/order/{orderNo}")
    public ApiResponse<Transaction> getTransactionByOrderNo(@PathVariable String orderNo) {
        Optional<Transaction> transaction = transactionService.getTransactionByOrderNo(orderNo);
        return transaction.map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.error(404, "订单不存在"));
    }
}
