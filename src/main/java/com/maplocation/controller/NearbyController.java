package com.maplocation.controller;

import com.maplocation.dto.ApiResponse;
import com.maplocation.dto.NearbyRequest;
import com.maplocation.dto.NearbyResponse;
import com.maplocation.model.NearbyQueryTask;
import com.maplocation.service.NearbyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/locations")
@RequiredArgsConstructor
public class NearbyController {

    private final NearbyService nearbyService;

    @PostMapping("/nearby")
    public ApiResponse<NearbyResponse> findNearbyLocations(@RequestBody NearbyRequest request) {
        NearbyResponse response = nearbyService.findNearbyLocations(request);
        return ApiResponse.success(response);
    }

    @PostMapping("/nearby/sync")
    public ApiResponse<NearbyResponse> findNearbySync(@RequestBody NearbyRequest request) {
        NearbyResponse response = nearbyService.findNearbySync(request);
        return ApiResponse.success(response);
    }

    @PostMapping("/nearby/async")
    public ApiResponse<NearbyResponse> findNearbyAsync(@RequestBody NearbyRequest request) {
        try {
            NearbyResponse response = nearbyService.findNearbyAsync(request);
            return ApiResponse.success(response);
        } catch (Exception e) {
            return ApiResponse.error(500, e.getMessage());
        }
    }

    @GetMapping("/nearby/tasks/{taskId}/status")
    public ApiResponse<Map<String, Object>> getNearbyTaskStatus(@PathVariable String taskId) {
        NearbyQueryTask.TaskStatus status = nearbyService.getAsyncTaskStatus(taskId);
        if (status == null) {
            return ApiResponse.error(404, "Task not found");
        }
        return ApiResponse.success(Map.of(
                "taskId", taskId,
                "status", status.name()
        ));
    }

    @GetMapping("/nearby/tasks/{taskId}/results")
    public ApiResponse<NearbyResponse> getNearbyResults(@PathVariable String taskId) {
        NearbyResponse response = nearbyService.getAsyncResults(taskId);
        if (response == null) {
            return ApiResponse.error(404, "Results not ready or task not found");
        }
        return ApiResponse.success(response);
    }
}
