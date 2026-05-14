package com.medical.appointment.controller;

import com.medical.appointment.dto.ApiResponse;
import com.medical.appointment.dto.ScheduleQueryResult;
import com.medical.appointment.entity.Schedule;
import com.medical.appointment.service.ScheduleService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/schedules")
public class ScheduleController {
    
    private final ScheduleService scheduleService;
    
    public ScheduleController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }
    
    @PostMapping
    public ResponseEntity<ApiResponse<Schedule>> createSchedule(@RequestBody Schedule schedule) {
        try {
            Schedule created = scheduleService.createSchedule(schedule);
            return ResponseEntity.ok(ApiResponse.success("排班创建成功", created));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.badRequest("创建排班失败: " + e.getMessage()));
        }
    }
    
    @GetMapping
    public ResponseEntity<ApiResponse<List<Schedule>>> getAllSchedules() {
        List<Schedule> schedules = scheduleService.getAllSchedules();
        return ResponseEntity.ok(ApiResponse.success(schedules));
    }
    
    @GetMapping("/query")
    public ResponseEntity<ApiResponse<Map<String, Object>>> querySchedules(
            @RequestParam(required = false) String hospitalId,
            @RequestParam(required = false) String departmentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        try {
            List<ScheduleQueryResult> results = scheduleService.querySchedules(hospitalId, departmentId, date);
            Map<String, Object> data = new HashMap<>();
            data.put("schedules", results);
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.badRequest("查询排班失败: " + e.getMessage()));
        }
    }
    
    @GetMapping("/department/{departmentId}")
    public ResponseEntity<ApiResponse<List<Schedule>>> getSchedulesByDepartment(
            @PathVariable String departmentId) {
        List<Schedule> schedules = scheduleService.getSchedulesByDepartment(departmentId);
        return ResponseEntity.ok(ApiResponse.success(schedules));
    }
    
    @GetMapping("/doctor/{doctorId}")
    public ResponseEntity<ApiResponse<List<Schedule>>> getSchedulesByDoctor(
            @PathVariable String doctorId) {
        List<Schedule> schedules = scheduleService.getSchedulesByDoctor(doctorId);
        return ResponseEntity.ok(ApiResponse.success(schedules));
    }
    
    @GetMapping("/date/{date}")
    public ResponseEntity<ApiResponse<List<Schedule>>> getSchedulesByDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        List<Schedule> schedules = scheduleService.getSchedulesByDate(date);
        return ResponseEntity.ok(ApiResponse.success(schedules));
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Schedule>> getScheduleById(@PathVariable String id) {
        return scheduleService.getScheduleById(id)
                .map(schedule -> ResponseEntity.ok(ApiResponse.success(schedule)))
                .orElse(ResponseEntity.status(404)
                        .body(ApiResponse.notFound("排班不存在")));
    }
    
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Schedule>> updateSchedule(
            @PathVariable String id, @RequestBody Schedule schedule) {
        try {
            Schedule updated = scheduleService.updateSchedule(id, schedule);
            return ResponseEntity.ok(ApiResponse.success("排班更新成功", updated));
        } catch (Exception e) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.notFound("更新排班失败: " + e.getMessage()));
        }
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteSchedule(@PathVariable String id) {
        scheduleService.deleteSchedule(id);
        return ResponseEntity.ok(ApiResponse.success("排班删除成功", null));
    }
}
