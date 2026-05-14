package com.movie.controller;

import com.movie.dto.ApiResponse;
import com.movie.dto.ScheduleQueryResponse;
import com.movie.service.ScheduleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/schedules")
public class ScheduleApiController {

    @Autowired
    private ScheduleService scheduleService;

    @GetMapping("/query")
    public ApiResponse<Map<String, Object>> querySchedules(
            @RequestParam(value = "movie_id") String movieId,
            @RequestParam(value = "date", required = false) 
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        
        List<ScheduleQueryResponse> schedules = scheduleService.querySchedules(movieId, date);
        
        List<Map<String, Object>> scheduleList = schedules.stream().map(s -> {
            Map<String, Object> item = new HashMap<>();
            item.put("cinema", s.getCinema());
            item.put("time", s.getTime());
            item.put("available", s.getAvailable());
            item.put("schedule_id", s.getScheduleId());
            item.put("price", s.getPrice());
            return item;
        }).toList();
        
        Map<String, Object> data = new HashMap<>();
        data.put("schedules", scheduleList);
        
        return ApiResponse.success(data);
    }
}
