package com.hotelbooking.controller;

import com.hotelbooking.dto.ApiResponse;
import com.hotelbooking.dto.CheckInRequest;
import com.hotelbooking.model.CheckIn;
import com.hotelbooking.service.CheckInQueueService;
import com.hotelbooking.service.CheckInQueueService.TaskResult;
import com.hotelbooking.service.CheckInService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/checkin")
public class CheckInController {

    private final CheckInService checkInService;
    private final CheckInQueueService checkInQueueService;

    public CheckInController(CheckInService checkInService, CheckInQueueService checkInQueueService) {
        this.checkInService = checkInService;
        this.checkInQueueService = checkInQueueService;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<Map<String, Object>>> registerCheckIn(@Valid @RequestBody CheckInRequest request) {
        try {
            CheckIn checkIn = checkInService.checkIn(request);
            
            Map<String, Object> data = new HashMap<>();
            data.put("checkin_id", checkIn.getCheckinId());
            data.put("status", checkIn.getCheckinStatus());
            data.put("checkin_time", checkIn.getCheckinTime());
            
            return ResponseEntity.ok(ApiResponse.success("入住登记成功", data));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @PostMapping("/register/async")
    public ResponseEntity<ApiResponse<Map<String, Object>>> registerCheckInAsync(@Valid @RequestBody CheckInRequest request) {
        try {
            String taskId = checkInQueueService.submitCheckInTask(request);
            long queueSize = checkInQueueService.getQueueSize();
            
            Map<String, Object> data = new HashMap<>();
            data.put("task_id", taskId);
            data.put("queue_size", queueSize);
            data.put("status", "submitted");
            data.put("message", "入住登记任务已提交到队列，请使用task_id查询处理状态");
            
            return ResponseEntity.ok(ApiResponse.success("入住登记任务已提交", data));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @GetMapping("/task/{taskId}")
    public ResponseEntity<ApiResponse<TaskResult>> getCheckInTaskStatus(@PathVariable String taskId) {
        TaskResult result = checkInQueueService.getTaskResult(taskId);
        if (result == null) {
            Map<String, Object> data = new HashMap<>();
            data.put("task_id", taskId);
            data.put("status", "processing");
            data.put("message", "任务正在处理中，请稍后查询");
            return ResponseEntity.ok(ApiResponse.success("任务处理中", null));
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/queue/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getQueueStatus() {
        long queueSize = checkInQueueService.getQueueSize();
        Map<String, Object> data = new HashMap<>();
        data.put("queue_size", queueSize);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @GetMapping("/{checkinId}")
    public ResponseEntity<ApiResponse<CheckIn>> getCheckIn(@PathVariable String checkinId) {
        return checkInService.getCheckInById(checkinId)
                .map(checkIn -> ResponseEntity.ok(ApiResponse.success(checkIn)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/booking/{bookingId}")
    public ResponseEntity<ApiResponse<CheckIn>> getCheckInByBooking(@PathVariable String bookingId) {
        return checkInService.getCheckInByBookingId(bookingId)
                .map(checkIn -> ResponseEntity.ok(ApiResponse.success(checkIn)))
                .orElse(ResponseEntity.notFound().build());
    }
}
