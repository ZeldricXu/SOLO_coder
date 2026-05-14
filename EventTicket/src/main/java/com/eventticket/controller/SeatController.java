package com.eventticket.controller;

import com.eventticket.dto.ApiResponse;
import com.eventticket.dto.SeatAssignRequest;
import com.eventticket.entity.Seat;
import com.eventticket.service.SeatService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/seats")
public class SeatController {

    @Autowired
    private SeatService seatService;

    @PostMapping
    public ApiResponse<Seat> createSeat(@RequestBody Seat seat) {
        Seat createdSeat = seatService.createSeat(seat);
        return ApiResponse.success(createdSeat);
    }

    @GetMapping("/{seatId}")
    public ApiResponse<Seat> getSeatById(@PathVariable String seatId) {
        Optional<Seat> seat = seatService.getSeatById(seatId);
        if (seat.isPresent()) {
            return ApiResponse.success(seat.get());
        }
        return ApiResponse.error(404, "座位不存在");
    }

    @GetMapping("/event/{eventId}")
    public ApiResponse<List<Seat>> getSeatsByEventId(@PathVariable String eventId) {
        List<Seat> seats = seatService.getSeatsByEventId(eventId);
        return ApiResponse.success(seats);
    }

    @GetMapping("/event/{eventId}/available")
    public ApiResponse<List<Seat>> getAvailableSeatsByEventId(
            @PathVariable String eventId,
            @RequestParam(required = false) String section) {
        List<Seat> seats = seatService.getAvailableSeatsByEventIdAndSection(eventId, section);
        return ApiResponse.success(seats);
    }

    @PostMapping("/assign")
    public ApiResponse<Seat> assignSeat(@Valid @RequestBody SeatAssignRequest request) {
        try {
            Seat seat = seatService.assignSeat(request);
            return ApiResponse.success(seat);
        } catch (RuntimeException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @PostMapping("/{seatId}/release")
    public ApiResponse<Boolean> releaseSeat(@PathVariable String seatId) {
        seatService.releaseSeat(seatId);
        return ApiResponse.success(true);
    }

    @GetMapping("/event/{eventId}/count")
    public ApiResponse<Long> getAvailableSeatCount(@PathVariable String eventId) {
        long count = seatService.countAvailableSeats(eventId);
        return ApiResponse.success(count);
    }
}
