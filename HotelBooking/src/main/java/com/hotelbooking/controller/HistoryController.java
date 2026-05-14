package com.hotelbooking.controller;

import com.hotelbooking.dto.ApiResponse;
import com.hotelbooking.model.Booking;
import com.hotelbooking.service.HistoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/history")
public class HistoryController {

    private final HistoryService historyService;

    public HistoryController(HistoryService historyService) {
        this.historyService = historyService;
    }

    @GetMapping("/customer")
    public ResponseEntity<ApiResponse<List<Booking>>> getCustomerHistory(@RequestParam String customerPhone) {
        List<Booking> history = historyService.getCustomerBookingHistory(customerPhone);
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    @GetMapping("/hotel/{hotelId}")
    public ResponseEntity<ApiResponse<List<Booking>>> getHotelHistory(@PathVariable String hotelId) {
        List<Booking> history = historyService.getHotelBookingHistory(hotelId);
        return ResponseEntity.ok(ApiResponse.success(history));
    }
}
