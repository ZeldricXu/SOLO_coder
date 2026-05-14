package com.paycenter.controller;

import com.paycenter.dto.ApiResponse;
import com.paycenter.entity.SettlementPeriod;
import com.paycenter.service.SettlementPeriodService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/periods")
public class PeriodController {

    @Autowired
    private SettlementPeriodService settlementPeriodService;

    @PostMapping
    public ApiResponse<SettlementPeriod> createPeriod(@RequestBody SettlementPeriod period) {
        SettlementPeriod created = settlementPeriodService.createPeriod(period);
        return ApiResponse.success(created);
    }

    @PutMapping("/{periodId}")
    public ApiResponse<SettlementPeriod> updatePeriod(
            @PathVariable String periodId,
            @RequestBody SettlementPeriod period) {
        period.setPeriodId(periodId);
        SettlementPeriod updated = settlementPeriodService.updatePeriod(period);
        return ApiResponse.success(updated);
    }

    @DeleteMapping("/{periodId}")
    public ApiResponse<Void> deletePeriod(@PathVariable String periodId) {
        settlementPeriodService.deletePeriod(periodId);
        return ApiResponse.success();
    }

    @GetMapping
    public ApiResponse<List<SettlementPeriod>> getAllPeriods() {
        List<SettlementPeriod> periods = settlementPeriodService.getAllEnabledPeriods();
        return ApiResponse.success(periods);
    }

    @GetMapping("/{periodId}")
    public ApiResponse<SettlementPeriod> getPeriod(@PathVariable String periodId) {
        Optional<SettlementPeriod> period = settlementPeriodService.getPeriodById(periodId);
        return period.map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.error(404, "结算周期不存在"));
    }
}
