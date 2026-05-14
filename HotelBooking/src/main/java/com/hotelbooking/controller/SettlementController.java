package com.hotelbooking.controller;

import com.hotelbooking.dto.ApiResponse;
import com.hotelbooking.model.Settlement;
import com.hotelbooking.service.SettlementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/settlements")
public class SettlementController {

    private final SettlementService settlementService;

    public SettlementController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @PostMapping("/checkout")
    public ResponseEntity<ApiResponse<Map<String, Object>>> checkoutAndSettle(
            @RequestParam String bookingId,
            @RequestParam(required = false, defaultValue = "cash") String paymentMethod) {
        try {
            Settlement settlement = settlementService.checkOutAndSettle(bookingId, paymentMethod);
            
            Map<String, Object> data = new HashMap<>();
            data.put("settlement_id", settlement.getSettlementId());
            data.put("room_charge", settlement.getRoomCharge());
            data.put("service_charge", settlement.getServiceCharge());
            data.put("total_amount", settlement.getTotalAmount());
            data.put("status", settlement.getSettlementStatus());
            data.put("settlement_time", settlement.getSettlementTime());
            
            return ResponseEntity.ok(ApiResponse.success("退房结算成功", data));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @GetMapping("/calculate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> calculateFee(@RequestParam String bookingId) {
        try {
            Settlement settlement = settlementService.calculateFee(bookingId);
            
            Map<String, Object> data = new HashMap<>();
            data.put("booking_id", settlement.getBookingId());
            data.put("room_charge", settlement.getRoomCharge());
            data.put("service_charge", settlement.getServiceCharge());
            data.put("total_amount", settlement.getTotalAmount());
            
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }
}
