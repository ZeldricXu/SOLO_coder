package com.schedulebook.controller;

import com.schedulebook.dto.ApiResponse;
import com.schedulebook.service.ScheduleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/schedules")
public class ScheduleController {
    
    @Autowired
    private ScheduleService scheduleService;
    
    @GetMapping("/query")
    public ResponseEntity<ApiResponse<Map<String, Object>>> querySchedule(
            @RequestParam String resourceId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        
        List<Map<String, Object>> slots = scheduleService.querySchedule(resourceId, date);
        
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("schedule_slots", slots);
        
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
