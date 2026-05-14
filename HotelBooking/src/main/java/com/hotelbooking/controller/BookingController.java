package com.hotelbooking.controller;

import com.hotelbooking.dto.ApiResponse;
import com.hotelbooking.dto.BookingCreateRequest;
import com.hotelbooking.model.Booking;
import com.hotelbooking.service.BookingService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping("/create")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createBooking(@Valid @RequestBody BookingCreateRequest request) {
        try {
            Booking booking = bookingService.createBooking(request);
            
            Map<String, Object> data = new HashMap<>();
            data.put("booking_id", booking.getBookingId());
            data.put("status", booking.getBookingStatus());
            data.put("booking_amount", booking.getBookingAmount());
            
            return ResponseEntity.ok(ApiResponse.success("预订创建成功", data));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @PostMapping("/{bookingId}/confirm")
    public ResponseEntity<ApiResponse<Map<String, Object>>> confirmBooking(@PathVariable String bookingId) {
        try {
            Booking booking = bookingService.confirmBooking(bookingId);
            
            Map<String, Object> data = new HashMap<>();
            data.put("booking_id", booking.getBookingId());
            data.put("status", booking.getBookingStatus());
            
            return ResponseEntity.ok(ApiResponse.success("预订确认成功", data));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @PostMapping("/{bookingId}/cancel")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cancelBooking(@PathVariable String bookingId) {
        try {
            Booking booking = bookingService.cancelBooking(bookingId);
            
            Map<String, Object> data = new HashMap<>();
            data.put("booking_id", booking.getBookingId());
            data.put("status", booking.getBookingStatus());
            
            return ResponseEntity.ok(ApiResponse.success("预订取消成功", data));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @GetMapping("/{bookingId}")
    public ResponseEntity<ApiResponse<Booking>> getBooking(@PathVariable String bookingId) {
        return bookingService.getBookingById(bookingId)
                .map(booking -> ResponseEntity.ok(ApiResponse.success(booking)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/hotel/{hotelId}")
    public ResponseEntity<ApiResponse<List<Booking>>> getBookingsByHotel(@PathVariable String hotelId) {
        List<Booking> bookings = bookingService.getBookingsByHotel(hotelId);
        return ResponseEntity.ok(ApiResponse.success(bookings));
    }

    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<Booking>>> getBookingHistory(@RequestParam String customerPhone) {
        List<Booking> history = bookingService.getBookingHistory(customerPhone);
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<ApiResponse<List<Booking>>> getBookingsByStatus(@PathVariable String status) {
        List<Booking> bookings = bookingService.getBookingsByStatus(status);
        return ResponseEntity.ok(ApiResponse.success(bookings));
    }
}
