package com.homeservice.controller;

import com.homeservice.dto.ApiResponse;
import com.homeservice.dto.BookingCreateRequest;
import com.homeservice.dto.BookingResponse;
import com.homeservice.entity.Booking;
import com.homeservice.enums.BookingStatus;
import com.homeservice.service.BookingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    @PostMapping("/create")
    public ApiResponse<BookingResponse> createBooking(@RequestBody BookingCreateRequest request) {
        BookingResponse response = bookingService.createBooking(request);
        return ApiResponse.success(response);
    }

    @GetMapping
    public ApiResponse<List<Booking>> getAllBookings() {
        List<Booking> bookings = bookingService.getAllBookings();
        return ApiResponse.success(bookings);
    }

    @GetMapping("/{bookingId}")
    public ApiResponse<Booking> getBooking(@PathVariable String bookingId) {
        Booking booking = bookingService.getBookingById(bookingId);
        return ApiResponse.success(booking);
    }

    @GetMapping("/staff/{staffId}")
    public ApiResponse<List<Booking>> getBookingsByStaff(@PathVariable String staffId) {
        List<Booking> bookings = bookingService.getBookingsByStaff(staffId);
        return ApiResponse.success(bookings);
    }

    @GetMapping("/customer/{customerId}")
    public ApiResponse<List<Booking>> getBookingsByCustomer(@PathVariable String customerId) {
        List<Booking> bookings = bookingService.getBookingsByCustomer(customerId);
        return ApiResponse.success(bookings);
    }

    @PostMapping("/{bookingId}/status/{status}")
    public ApiResponse<Booking> updateBookingStatus(@PathVariable String bookingId, @PathVariable BookingStatus status) {
        Booking booking = bookingService.updateBookingStatus(bookingId, status);
        return ApiResponse.success(booking);
    }
}
