package com.travelbooking.controller;

import com.travelbooking.dto.ApiResponse;
import com.travelbooking.model.Settlement;
import com.travelbooking.service.SettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/settlements")
@RequiredArgsConstructor
public class SettlementController {

    private final SettlementService settlementService;

    @GetMapping
    public ApiResponse<List<Settlement>> getAllSettlements() {
        return ApiResponse.success(settlementService.getAllSettlements());
    }

    @GetMapping("/{id}")
    public ApiResponse<Settlement> getSettlementById(@PathVariable String id) {
        return settlementService.getSettlementById(id)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "结算记录不存在"));
    }

    @GetMapping("/booking/{bookingId}")
    public ApiResponse<List<Settlement>> getSettlementsByBookingId(@PathVariable String bookingId) {
        return ApiResponse.success(settlementService.getSettlementsByBookingId(bookingId));
    }

    @GetMapping("/tourist/{touristId}")
    public ApiResponse<List<Settlement>> getSettlementsByTouristId(@PathVariable String touristId) {
        return ApiResponse.success(settlementService.getSettlementsByTouristId(touristId));
    }

    @PostMapping
    public ApiResponse<Settlement> createSettlement(
            @RequestParam String bookingId,
            @RequestParam(defaultValue = "wechat") String paymentMethod) {
        Settlement created = settlementService.createSettlement(bookingId, paymentMethod);
        return ApiResponse.success(created);
    }

    @PutMapping("/{id}")
    public ApiResponse<Settlement> updateSettlement(@PathVariable String id, @RequestBody Settlement settlement) {
        Settlement updated = settlementService.updateSettlement(id, settlement);
        return ApiResponse.success(updated);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteSettlement(@PathVariable String id) {
        settlementService.deleteSettlement(id);
        return ApiResponse.success(null);
    }
}
