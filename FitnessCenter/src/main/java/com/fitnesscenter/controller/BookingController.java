package com.fitnesscenter.controller;

import com.fitnesscenter.dto.ApiResponse;
import com.fitnesscenter.dto.BookingRequest;
import com.fitnesscenter.dto.BookingResponse;
import com.fitnesscenter.model.Booking;
import com.fitnesscenter.service.BookingService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping("/create")
    public ApiResponse<BookingResponse> createBooking(@RequestBody BookingRequest request) {
        BookingResponse response = bookingService.createBooking(request);
        return ApiResponse.success(response);
    }

    @GetMapping("/{bookingId}")
    public ApiResponse<Booking> getBookingById(@PathVariable String bookingId) {
        Booking booking = bookingService.getBookingById(bookingId);
        return ApiResponse.success(booking);
    }

    @GetMapping("/member/{memberId}")
    public ApiResponse<List<Booking>> getBookingsByMemberId(@PathVariable String memberId) {
        List<Booking> bookings = bookingService.getBookingsByMemberId(memberId);
        return ApiResponse.success(bookings);
    }

    @GetMapping("/course/{courseId}")
    public ApiResponse<List<Booking>> getBookingsByCourseId(@PathVariable String courseId) {
        List<Booking> bookings = bookingService.getBookingsByCourseId(courseId);
        return ApiResponse.success(bookings);
    }

    @GetMapping
    public ApiResponse<List<Booking>> getAllBookings() {
        List<Booking> bookings = bookingService.getAllBookings();
        return ApiResponse.success(bookings);
    }

    @PutMapping("/{bookingId}/status")
    public ApiResponse<Booking> updateBookingStatus(@PathVariable String bookingId, @RequestParam String status) {
        Booking booking = bookingService.updateBookingStatus(bookingId, status);
        return ApiResponse.success(booking);
    }
}
