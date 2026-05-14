package com.movie.controller;

import com.movie.dto.ApiResponse;
import com.movie.dto.ScheduleCreateRequest;
import com.movie.entity.Schedule;
import com.movie.scheduler.ScheduleAsyncWorker;
import com.movie.service.ScheduleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/schedules")
public class ScheduleController {

    @Autowired
    private ScheduleService scheduleService;

    @GetMapping
    public ApiResponse<List<Schedule>> list() {
        return ApiResponse.success(scheduleService.getAllSchedules());
    }

    @GetMapping("/{scheduleId}")
    public ApiResponse<Schedule> get(@PathVariable String scheduleId) {
        return ApiResponse.success(scheduleService.getScheduleOrThrow(scheduleId));
    }

    @GetMapping("/async/tasks")
    public ApiResponse<Map<String, Object>> getAsyncTaskStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("pending", scheduleService.getPendingTaskCount());
        stats.put("processing", scheduleService.getProcessingTaskCount());
        stats.put("completed", scheduleService.getCompletedTaskCount());
        stats.put("failed", scheduleService.getFailedTaskCount());
        return ApiResponse.success(stats);
    }

    @GetMapping("/async/task/{taskId}")
    public ApiResponse<ScheduleAsyncWorker.TaskStatus> getAsyncTaskStatus(@PathVariable String taskId) {
        ScheduleAsyncWorker.TaskStatus status = scheduleService.getScheduleTaskStatus(taskId);
        if (status == null) {
            return ApiResponse.error(404, "任务不存在");
        }
        return ApiResponse.success(status);
    }

    @PostMapping
    public ApiResponse<Schedule> create(@RequestBody ScheduleCreateRequest request) {
        return ApiResponse.success(scheduleService.createSchedule(request));
    }

    @PostMapping("/async")
    public ApiResponse<Map<String, String>> createAsync(@RequestBody ScheduleCreateRequest request) {
        String taskId = scheduleService.createScheduleAsync(request);
        Map<String, String> result = new HashMap<>();
        result.put("taskId", taskId);
        result.put("status", "pending");
        result.put("message", "排片配置任务已提交，后台正在处理");
        return ApiResponse.success(result);
    }

    @PutMapping("/{scheduleId}")
    public ApiResponse<Schedule> update(@PathVariable String scheduleId, @RequestBody ScheduleCreateRequest request) {
        return ApiResponse.success(scheduleService.updateSchedule(scheduleId, request));
    }

    @PostMapping("/{scheduleId}/close")
    public ApiResponse<Void> close(@PathVariable String scheduleId) {
        scheduleService.closeSchedule(scheduleId);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{scheduleId}")
    public ApiResponse<Void> delete(@PathVariable String scheduleId) {
        scheduleService.deleteSchedule(scheduleId);
        return ApiResponse.success(null);
    }

    @PostMapping("/async/clear-completed")
    public ApiResponse<Void> clearCompletedTasks() {
        scheduleService.clearCompletedTasks();
        return ApiResponse.success(null);
    }
}
