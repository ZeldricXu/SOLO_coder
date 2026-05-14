package com.homeservice.controller;

import com.homeservice.dto.ApiResponse;
import com.homeservice.dto.SettlementProcessRequest;
import com.homeservice.dto.SettlementResponse;
import com.homeservice.entity.Settlement;
import com.homeservice.service.SettlementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/settlements")
public class SettlementController {

    @Autowired
    private SettlementService settlementService;

    @PostMapping("/process")
    public ApiResponse<SettlementResponse> processSettlement(@RequestBody SettlementProcessRequest request) {
        SettlementResponse response = settlementService.processSettlement(request);
        return ApiResponse.success(response);
    }

    @GetMapping
    public ApiResponse<List<Settlement>> getAllSettlements() {
        List<Settlement> settlements = settlementService.getAllSettlements();
        return ApiResponse.success(settlements);
    }

    @GetMapping("/{settlementId}")
    public ApiResponse<Settlement> getSettlement(@PathVariable String settlementId) {
        Settlement settlement = settlementService.getSettlementById(settlementId);
        return ApiResponse.success(settlement);
    }

    @GetMapping("/booking/{bookingId}")
    public ApiResponse<Settlement> getSettlementByBooking(@PathVariable String bookingId) {
        Settlement settlement = settlementService.getSettlementByBookingId(bookingId);
        return ApiResponse.success(settlement);
    }

    @GetMapping("/staff/{staffId}")
    public ApiResponse<List<Settlement>> getSettlementsByStaff(@PathVariable String staffId) {
        List<Settlement> settlements = settlementService.getSettlementsByStaffId(staffId);
        return ApiResponse.success(settlements);
    }
}
