package com.schedulebook.controller;

import com.schedulebook.dto.ApiResponse;
import com.schedulebook.dto.CancelBookingRequest;
import com.schedulebook.dto.CreateBookingRequest;
import com.schedulebook.model.Booking;
import com.schedulebook.service.BookingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {
    
    @Autowired
    private BookingService bookingService;
    
    @PostMapping("/create")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createBooking(
            @Valid @RequestBody CreateBookingRequest request) {
        Map<String, Object> result = bookingService.createBooking(request);
        return ResponseEntity.ok(ApiResponse.success(result));
    }
    
    @PostMapping("/cancel")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cancelBooking(
            @Valid @RequestBody CancelBookingRequest request) {
        Map<String, Object> result = bookingService.cancelBooking(request);
        return ResponseEntity.ok(ApiResponse.success(result));
    }
    
    @GetMapping("/{bookingId}")
    public ResponseEntity<ApiResponse<Booking>> getBooking(@PathVariable String bookingId) {
        Booking booking = bookingService.getBooking(bookingId);
        return ResponseEntity.ok(ApiResponse.success(booking));
    }
}
