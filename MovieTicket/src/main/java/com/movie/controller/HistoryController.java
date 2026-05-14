package com.movie.controller;

import com.movie.dto.ApiResponse;
import com.movie.entity.TicketHistory;
import com.movie.service.HistoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/admin/history")
public class HistoryController {

    @Autowired
    private HistoryService historyService;

    @GetMapping("/ticket/{ticketId}")
    public ApiResponse<List<TicketHistory>> getByTicket(@PathVariable String ticketId) {
        return ApiResponse.success(historyService.getHistoryByTicketId(ticketId));
    }

    @GetMapping("/user/{userId}")
    public ApiResponse<List<TicketHistory>> getByUser(@PathVariable String userId) {
        return ApiResponse.success(historyService.getHistoryByUserId(userId));
    }

    @GetMapping("/user/{userId}/range")
    public ApiResponse<List<TicketHistory>> getByUserAndRange(
            @PathVariable String userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        return ApiResponse.success(historyService.getHistoryByUserIdAndTimeRange(userId, start, end));
    }

    @GetMapping("/movie/{movieId}")
    public ApiResponse<List<TicketHistory>> getByMovie(@PathVariable String movieId) {
        return ApiResponse.success(historyService.getHistoryByMovieId(movieId));
    }

    @GetMapping("/cinema/{cinemaId}")
    public ApiResponse<List<TicketHistory>> getByCinema(@PathVariable String cinemaId) {
        return ApiResponse.success(historyService.getHistoryByCinemaId(cinemaId));
    }
}
