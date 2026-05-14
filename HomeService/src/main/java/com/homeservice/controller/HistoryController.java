package com.homeservice.controller;

import com.homeservice.dto.ApiResponse;
import com.homeservice.entity.ServiceHistory;
import com.homeservice.service.ServiceHistoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/history")
public class HistoryController {

    @Autowired
    private ServiceHistoryService serviceHistoryService;

    @GetMapping
    public ApiResponse<List<ServiceHistory>> getAllHistory() {
        List<ServiceHistory> history = serviceHistoryService.getAllHistory();
        return ApiResponse.success(history);
    }

    @GetMapping("/booking/{bookingId}")
    public ApiResponse<List<ServiceHistory>> getHistoryByBooking(@PathVariable String bookingId) {
        List<ServiceHistory> history = serviceHistoryService.getHistoryByBookingId(bookingId);
        return ApiResponse.success(history);
    }

    @GetMapping("/staff/{staffId}")
    public ApiResponse<List<ServiceHistory>> getHistoryByStaff(@PathVariable String staffId) {
        List<ServiceHistory> history = serviceHistoryService.getHistoryByStaffId(staffId);
        return ApiResponse.success(history);
    }

    @GetMapping("/customer/{customerId}")
    public ApiResponse<List<ServiceHistory>> getHistoryByCustomer(@PathVariable String customerId) {
        List<ServiceHistory> history = serviceHistoryService.getHistoryByCustomerId(customerId);
        return ApiResponse.success(history);
    }

    @GetMapping("/type/{historyType}")
    public ApiResponse<List<ServiceHistory>> getHistoryByType(@PathVariable String historyType) {
        List<ServiceHistory> history = serviceHistoryService.getHistoryByType(historyType);
        return ApiResponse.success(history);
    }
}
