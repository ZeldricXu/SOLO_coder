package com.paycenter.controller;

import com.paycenter.dto.ApiResponse;
import com.paycenter.dto.RefundRequest;
import com.paycenter.dto.RefundResponse;
import com.paycenter.entity.Refund;
import com.paycenter.service.RefundService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/pay")
public class RefundController {

    @Autowired
    private RefundService refundService;

    @PostMapping("/refund")
    public ApiResponse<RefundResponse> createRefund(@Valid @RequestBody RefundRequest request) {
        RefundResponse response = refundService.createRefund(request);
        return ApiResponse.success(response);
    }

    @PostMapping("/refund/execute/{refundId}")
    public ApiResponse<Refund> executeRefund(
            @PathVariable String refundId,
            @RequestParam boolean success) {
        Refund refund = refundService.executeRefund(refundId, success);
        return ApiResponse.success(refund);
    }

    @GetMapping("/refund/{refundId}")
    public ApiResponse<Refund> getRefund(@PathVariable String refundId) {
        Optional<Refund> refund = refundService.getRefundById(refundId);
        return refund.map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.error(404, "退款记录不存在"));
    }

    @GetMapping("/refund/transaction/{transactionId}")
    public ApiResponse<List<Refund>> getRefundsByTransaction(@PathVariable String transactionId) {
        List<Refund> refunds = refundService.getRefundsByTransaction(transactionId);
        return ApiResponse.success(refunds);
    }
}
