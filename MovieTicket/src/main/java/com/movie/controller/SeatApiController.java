package com.movie.controller;

import com.movie.dto.ApiResponse;
import com.movie.dto.SeatQueryResponse;
import com.movie.service.SeatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/seats")
public class SeatApiController {

    @Autowired
    private SeatService seatService;

    @GetMapping("/query")
    public ApiResponse<Map<String, Object>> querySeats(
            @RequestParam(value = "schedule_id") String scheduleId) {
        
        List<SeatQueryResponse> seats = seatService.getSeatResponsesByScheduleId(scheduleId);
        
        List<Map<String, Object>> seatList = seats.stream().map(s -> {
            Map<String, Object> item = new HashMap<>();
            item.put("seat_id", s.getSeatId());
            item.put("seat_number", s.getSeatNumber());
            item.put("status", s.getSeatStatus());
            item.put("row", s.getSeatRow());
            item.put("column", s.getSeatColumn());
            item.put("price", s.getSeatPrice());
            return item;
        }).toList();
        
        Map<String, Object> data = new HashMap<>();
        data.put("seats", seatList);
        
        return ApiResponse.success(data);
    }
}
