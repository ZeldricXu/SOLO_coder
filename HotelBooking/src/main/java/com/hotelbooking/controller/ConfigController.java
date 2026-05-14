package com.hotelbooking.controller;

import com.hotelbooking.config.FeeValidationConfig;
import com.hotelbooking.config.LockTimeoutConfig;
import com.hotelbooking.config.RoomTypeConfig;
import com.hotelbooking.dto.ApiResponse;
import com.hotelbooking.service.CheckInQueueService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/config")
public class ConfigController {

    @Autowired
    private LockTimeoutConfig lockTimeoutConfig;

    @Autowired
    private FeeValidationConfig feeValidationConfig;

    @Autowired
    private RoomTypeConfig roomTypeConfig;

    @Autowired
    private CheckInQueueService checkInQueueService;

    @GetMapping("/lock/timeouts")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getLockTimeoutConfigs() {
        Map<String, Object> data = new HashMap<>();
        data.put("defaultLevel", lockTimeoutConfig.getDefaultLevel());
        data.put("timeouts", lockTimeoutConfig.getTimeouts());
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @GetMapping("/lock/timeouts/{level}")
    public ResponseEntity<ApiResponse<Long>> getLockTimeoutByLevel(@PathVariable String level) {
        long timeout = lockTimeoutConfig.getTimeoutMillis(level);
        return ResponseEntity.ok(ApiResponse.success(timeout));
    }

    @GetMapping("/fee/validation")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getFeeValidationConfigs() {
        Map<String, Object> data = new HashMap<>();
        data.put("tolerance", feeValidationConfig.getTolerance());
        data.put("types", feeValidationConfig.getTypes());
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @GetMapping("/fee/validation/{type}")
    public ResponseEntity<ApiResponse<FeeValidationConfig.FeeTypeConfig>> getFeeValidationConfigByType(
            @PathVariable String type) {
        FeeValidationConfig.FeeTypeConfig config = feeValidationConfig.getConfig(type);
        return ResponseEntity.ok(ApiResponse.success(config));
    }

    @GetMapping("/room/types")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAllRoomTypes() {
        Map<String, Object> data = new HashMap<>();
        data.put("defaultType", roomTypeConfig.getDefaultType());
        data.put("types", roomTypeConfig.getTypes());
        data.put("allTypes", roomTypeConfig.getAllRoomTypes());
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @GetMapping("/room/types/{type}")
    public ResponseEntity<ApiResponse<RoomTypeConfig.RoomTypeConfigEntry>> getRoomTypeConfig(
            @PathVariable String type) {
        RoomTypeConfig.RoomTypeConfigEntry config = roomTypeConfig.getTypeConfig(type);
        if (config == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ApiResponse.success(config));
    }

    @GetMapping("/room/types/validate/{type}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> validateRoomType(@PathVariable String type) {
        boolean valid = roomTypeConfig.isValidRoomType(type);
        Map<String, Object> data = new HashMap<>();
        data.put("roomType", type);
        data.put("valid", valid);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @GetMapping("/checkin/queue/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCheckInQueueStatus() {
        Map<String, Object> data = new HashMap<>();
        data.put("queueSize", checkInQueueService.getQueueSize());
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @GetMapping("/checkin/task/{taskId}")
    public ResponseEntity<ApiResponse<CheckInQueueService.TaskResult>> getCheckInTaskResult(
            @PathVariable String taskId) {
        CheckInQueueService.TaskResult result = checkInQueueService.getTaskResult(taskId);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
