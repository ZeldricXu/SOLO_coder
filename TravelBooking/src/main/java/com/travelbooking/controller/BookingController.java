package com.travelbooking.controller;

import com.travelbooking.dto.ApiResponse;
import com.travelbooking.dto.CreateBookingRequest;
import com.travelbooking.dto.CreateBookingResponse;
import com.travelbooking.model.Booking;
import com.travelbooking.service.BookingService;
import com.travelbooking.service.DistributedLockService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    @PostMapping("/create")
    public ApiResponse<CreateBookingResponse> createBooking(
            @Valid @RequestBody CreateBookingRequest request,
            @RequestParam(required = false, defaultValue = "NORMAL") String urgency) {
        if (request.getBookingCount() == null) {
            request.setBookingCount(1);
        }
        
        DistributedLockService.BookingUrgency bookingUrgency = 
                DistributedLockService.BookingUrgency.fromString(urgency);
        
        CreateBookingResponse response = bookingService.createBooking(request, bookingUrgency);
        return ApiResponse.success(response);
    }

    @GetMapping
    public ApiResponse<List<Booking>> getAllBookings() {
        return ApiResponse.success(bookingService.getAllBookings());
    }

    @GetMapping("/{id}")
    public ApiResponse<Booking> getBookingById(@PathVariable String id) {
        return bookingService.getBookingById(id)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "预订不存在"));
    }

    @GetMapping("/tourist/{touristId}")
    public ApiResponse<List<Booking>> getBookingsByTouristId(@PathVariable String touristId) {
        return ApiResponse.success(bookingService.getBookingsByTouristId(touristId));
    }

    @GetMapping("/route/{routeId}")
    public ApiResponse<List<Booking>> getBookingsByRouteId(@PathVariable String routeId) {
        return ApiResponse.success(bookingService.getBookingsByRouteId(routeId));
    }

    @PutMapping("/{id}")
    public ApiResponse<Booking> updateBooking(@PathVariable String id, @RequestBody Booking booking) {
        Booking updated = bookingService.updateBooking(id, booking);
        return ApiResponse.success(updated);
    }

    @PutMapping("/{id}/status")
    public ApiResponse<Booking> updateBookingStatus(
            @PathVariable String id,
            @RequestParam String status) {
        Booking updated = bookingService.updateBookingStatus(id, status);
        return ApiResponse.success(updated);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteBooking(@PathVariable String id) {
        bookingService.deleteBooking(id);
        return ApiResponse.success(null);
    }
}
