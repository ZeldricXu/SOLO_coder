package com.houserental.controller;

import com.houserental.dto.ApiResponse;
import com.houserental.dto.PaymentDTO;
import com.houserental.entity.Payment;
import com.houserental.service.RentService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/rents")
public class RentController {

    @Autowired
    private RentService rentService;

    @PostMapping("/pay")
    public ApiResponse<Map<String, Object>> payRent(@Valid @RequestBody PaymentDTO dto) {
        Payment payment = rentService.processPayment(dto);

        Map<String, Object> result = new HashMap<>();
        result.put("payment_id", payment.getPaymentId());
        result.put("contract_id", payment.getContractId());
        result.put("amount", payment.getPaymentAmount());
        result.put("period", payment.getPaymentPeriod());
        result.put("status", payment.getPaymentStatus());
        result.put("paid_at", payment.getPaidAt());

        return ApiResponse.success(result);
    }

    @GetMapping("/{paymentId}")
    public ApiResponse<Payment> getPaymentById(@PathVariable String paymentId) {
        Payment payment = rentService.getPaymentById(paymentId);
        return ApiResponse.success(payment);
    }

    @GetMapping("/list")
    public ApiResponse<List<Payment>> getAllPayments() {
        List<Payment> payments = rentService.getAllPayments();
        return ApiResponse.success(payments);
    }

    @GetMapping("/contract/{contractId}")
    public ApiResponse<List<Payment>> getPaymentsByContract(@PathVariable String contractId) {
        List<Payment> payments = rentService.getPaymentsByContract(contractId);
        return ApiResponse.success(payments);
    }

    @GetMapping("/tenant/{tenantId}")
    public ApiResponse<List<Payment>> getPaymentsByTenant(@PathVariable String tenantId) {
        List<Payment> payments = rentService.getPaymentsByTenant(tenantId);
        return ApiResponse.success(payments);
    }

    @GetMapping("/pending")
    public ApiResponse<List<Payment>> getPendingPayments() {
        List<Payment> payments = rentService.getPendingPayments();
        return ApiResponse.success(payments);
    }

    @GetMapping("/paid")
    public ApiResponse<List<Payment>> getPaidPayments() {
        List<Payment> payments = rentService.getPaidPayments();
        return ApiResponse.success(payments);
    }

    @PostMapping("/{paymentId}/failed")
    public ApiResponse<Payment> markPaymentAsFailed(
            @PathVariable String paymentId,
            @RequestParam(required = false) String reason) {
        Payment payment = rentService.markPaymentAsFailed(paymentId, reason);
        return ApiResponse.success(payment);
    }

    @PostMapping("/{paymentId}/refund")
    public ApiResponse<Payment> refundPayment(
            @PathVariable String paymentId,
            @RequestParam(required = false) String reason) {
        Payment payment = rentService.refundPayment(paymentId, reason);
        return ApiResponse.success(payment);
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> getRentStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalPayments", rentService.countTotalPayments());
        stats.put("pendingPayments", rentService.countPendingPayments());
        stats.put("paidPayments", rentService.countPaidPayments());
        stats.put("failedPayments", rentService.countFailedPayments());
        stats.put("totalPaidAmount", rentService.getTotalPaidAmount());
        return ApiResponse.success(stats);
    }
}
