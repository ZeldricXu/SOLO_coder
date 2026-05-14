package com.eventticket.controller;

import com.eventticket.dto.ApiResponse;
import com.eventticket.entity.EventSchedule;
import com.eventticket.service.EventScheduleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/schedules")
public class ScheduleController {

    @Autowired
    private EventScheduleService eventScheduleService;

    @PostMapping
    public ApiResponse<EventSchedule> createSchedule(@RequestBody EventSchedule schedule) {
        EventSchedule createdSchedule = eventScheduleService.createSchedule(schedule);
        return ApiResponse.success(createdSchedule);
    }

    @GetMapping("/{scheduleId}")
    public ApiResponse<EventSchedule> getScheduleById(@PathVariable String scheduleId) {
        Optional<EventSchedule> schedule = eventScheduleService.getScheduleById(scheduleId);
        if (schedule.isPresent()) {
            return ApiResponse.success(schedule.get());
        }
        return ApiResponse.error(404, "日程不存在");
    }

    @GetMapping("/event/{eventId}")
    public ApiResponse<List<EventSchedule>> getSchedulesByEventId(@PathVariable String eventId) {
        List<EventSchedule> schedules = eventScheduleService.getSchedulesByEventId(eventId);
        return ApiResponse.success(schedules);
    }

    @PutMapping("/{scheduleId}")
    public ApiResponse<EventSchedule> updateSchedule(
            @PathVariable String scheduleId,
            @RequestBody EventSchedule updatedSchedule) {
        EventSchedule schedule = eventScheduleService.updateSchedule(scheduleId, updatedSchedule);
        if (schedule != null) {
            return ApiResponse.success(schedule);
        }
        return ApiResponse.error(404, "日程不存在");
    }

    @DeleteMapping("/{scheduleId}")
    public ApiResponse<Boolean> deleteSchedule(@PathVariable String scheduleId) {
        boolean deleted = eventScheduleService.deleteSchedule(scheduleId);
        if (deleted) {
            return ApiResponse.success(true);
        }
        return ApiResponse.error(404, "日程不存在");
    }
}
