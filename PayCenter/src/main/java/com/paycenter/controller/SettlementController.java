package com.paycenter.controller;

import com.paycenter.dto.ApiResponse;
import com.paycenter.dto.SettlementQueryRequest;
import com.paycenter.entity.Settlement;
import com.paycenter.service.SettlementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/settlement")
public class SettlementController {

    @Autowired
    private SettlementService settlementService;

    @GetMapping("/query")
    public ApiResponse<Map<String, Object>> querySettlements(
            @RequestParam String merchantId,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate) {
        
        SettlementQueryRequest request = new SettlementQueryRequest();
        request.setMerchantId(merchantId);
        request.setStartDate(startDate);
        request.setEndDate(endDate);
        
        List<Settlement> settlements = settlementService.querySettlements(request);
        
        Map<String, Object> result = new HashMap<>();
        result.put("settlements", settlements);
        
        return ApiResponse.success(result);
    }

    @PostMapping("/execute/{merchantId}")
    public ApiResponse<Settlement> executeSettlement(
            @PathVariable String merchantId,
            @RequestParam(required = false) LocalDate settlementDate) {
        
        if (settlementDate == null) {
            settlementDate = LocalDate.now().minusDays(1);
        }
        
        Settlement settlement = settlementService.calculateAndExecuteSettlement(merchantId, settlementDate);
        if (settlement == null) {
            return ApiResponse.error(204, "未达到结算条件");
        }
        
        return ApiResponse.success(settlement);
    }

    @GetMapping("/{settlementId}")
    public ApiResponse<Settlement> getSettlement(@PathVariable String settlementId) {
        Optional<Settlement> settlement = settlementService.getSettlementById(settlementId);
        return settlement.map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.error(404, "结算记录不存在"));
    }

    @GetMapping("/merchant/{merchantId}")
    public ApiResponse<List<Settlement>> getSettlementsByMerchant(@PathVariable String merchantId) {
        List<Settlement> settlements = settlementService.getSettlementsByMerchant(merchantId);
        return ApiResponse.success(settlements);
    }
}
