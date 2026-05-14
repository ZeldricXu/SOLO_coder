package com.parking.controller;

import com.parking.dto.ApiResponse;
import com.parking.dto.PaymentRequest;
import com.parking.dto.PaymentResponse;
import com.parking.entity.SettlementRecord;
import com.parking.service.SettlementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/settlements")
public class SettlementController {

    @Autowired
    private SettlementService settlementService;

    @PostMapping("/pay")
    public ApiResponse<PaymentResponse> processPayment(@RequestBody PaymentRequest request) {
        PaymentResponse response = settlementService.processPayment(request);
        return ApiResponse.success(response);
    }

    @GetMapping("/{settlementId}")
    public ApiResponse<SettlementRecord> getSettlement(@PathVariable String settlementId) {
        SettlementRecord settlement = settlementService.getSettlementById(settlementId);
        return ApiResponse.success(settlement);
    }

    @GetMapping("/list")
    public ApiResponse<List<SettlementRecord>> listSettlements() {
        List<SettlementRecord> settlements = settlementService.getAllSettlements();
        return ApiResponse.success(settlements);
    }

    @GetMapping("/status/{status}")
    public ApiResponse<List<SettlementRecord>> listSettlementsByStatus(@PathVariable String status) {
        List<SettlementRecord> settlements = settlementService.getSettlementsByStatus(status);
        return ApiResponse.success(settlements);
    }

    @PostMapping("/{settlementId}/retry")
    public ApiResponse<SettlementRecord> retryPayment(@PathVariable String settlementId) {
        SettlementRecord settlement = settlementService.retryPayment(settlementId);
        return ApiResponse.success(settlement);
    }
}
