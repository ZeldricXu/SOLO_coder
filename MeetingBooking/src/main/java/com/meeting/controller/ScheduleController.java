package com.meeting.controller;

import com.meeting.dto.ApiResponse;
import com.meeting.entity.Schedule;
import com.meeting.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/schedules")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Schedule>>> getAllSchedules() {
        List<Schedule> schedules = scheduleService.getAllSchedules();
        return ResponseEntity.ok(ApiResponse.success(schedules));
    }

    @GetMapping("/{scheduleId}")
    public ResponseEntity<ApiResponse<Schedule>> getScheduleById(@PathVariable String scheduleId) {
        Schedule schedule = scheduleService.getScheduleById(scheduleId);
        return ResponseEntity.ok(ApiResponse.success(schedule));
    }

    @GetMapping("/meeting/{meetingId}")
    public ResponseEntity<ApiResponse<Schedule>> getScheduleByMeeting(@PathVariable String meetingId) {
        Schedule schedule = scheduleService.getScheduleByMeetingId(meetingId);
        return ResponseEntity.ok(ApiResponse.success(schedule));
    }

    @GetMapping("/room/{roomId}")
    public ResponseEntity<ApiResponse<List<Schedule>>> getSchedulesByRoom(@PathVariable String roomId) {
        List<Schedule> schedules = scheduleService.getSchedulesByRoomId(roomId);
        return ResponseEntity.ok(ApiResponse.success(schedules));
    }

    @GetMapping("/date/{date}")
    public ResponseEntity<ApiResponse<List<Schedule>>> getSchedulesByDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        List<Schedule> schedules = scheduleService.getSchedulesByDate(date);
        return ResponseEntity.ok(ApiResponse.success(schedules));
    }

    @GetMapping("/room/{roomId}/date/{date}")
    public ResponseEntity<ApiResponse<List<Schedule>>> getSchedulesByRoomAndDate(
            @PathVariable String roomId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        List<Schedule> schedules = scheduleService.getSchedulesByRoomAndDate(roomId, date);
        return ResponseEntity.ok(ApiResponse.success(schedules));
    }

    @PutMapping("/{scheduleId}/status")
    public ResponseEntity<ApiResponse<Schedule>> updateScheduleStatus(
            @PathVariable String scheduleId,
            @RequestParam String status) {
        Schedule schedule = scheduleService.updateScheduleStatus(scheduleId, status);
        return ResponseEntity.ok(ApiResponse.success("日程状态更新成功", schedule));
    }

    @DeleteMapping("/{scheduleId}")
    public ResponseEntity<ApiResponse<Void>> deleteSchedule(@PathVariable String scheduleId) {
        scheduleService.deleteSchedule(scheduleId);
        return ResponseEntity.ok(ApiResponse.success("日程删除成功", null));
    }
}
