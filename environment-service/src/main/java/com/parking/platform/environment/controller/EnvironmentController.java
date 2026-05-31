package com.parking.platform.environment.controller;

import com.parking.platform.common.dto.ApiResponse;
import com.parking.platform.environment.entity.PreviewEnvironment;
import com.parking.platform.environment.service.EnvironmentService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/environments")
public class EnvironmentController {

    private final EnvironmentService environmentService;

    public EnvironmentController(EnvironmentService environmentService) {
        this.environmentService = environmentService;
    }

    @PostMapping
    public ApiResponse<PreviewEnvironment> create(@RequestBody PreviewEnvironment env) {
        return ApiResponse.created(environmentService.create(env));
    }

    @GetMapping("/{id}")
    public ApiResponse<PreviewEnvironment> get(@PathVariable String id) {
        PreviewEnvironment env = environmentService.get(id);
        return env != null ? ApiResponse.success(env) : ApiResponse.notFound("Environment not found");
    }

    @GetMapping
    public ApiResponse<List<PreviewEnvironment>> list(
            @RequestParam(required = false) String owner,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String template) {
        return ApiResponse.success(environmentService.list(owner, status, template));
    }

    @PostMapping("/{id}/start")
    public ApiResponse<PreviewEnvironment> start(@PathVariable String id) {
        return ApiResponse.success(environmentService.start(id));
    }

    @PostMapping("/{id}/stop")
    public ApiResponse<PreviewEnvironment> stop(@PathVariable String id) {
        return ApiResponse.success(environmentService.stop(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        boolean deleted = environmentService.delete(id);
        return deleted ? ApiResponse.noContent() : ApiResponse.notFound("Environment not found");
    }

    @PostMapping("/{id}/extend")
    public ApiResponse<PreviewEnvironment> extend(@PathVariable String id, @RequestParam(defaultValue = "1440") long minutes) {
        return ApiResponse.success(environmentService.extend(id, minutes));
    }

    @PostMapping("/{id}/heartbeat")
    public ApiResponse<PreviewEnvironment> heartbeat(@PathVariable String id) {
        return ApiResponse.success(environmentService.heartbeat(id));
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Long>> getStats() {
        return ApiResponse.success(environmentService.getStatistics());
    }

    @GetMapping("/usage/{owner}")
    public ApiResponse<Map<String, Object>> getUsage(@PathVariable String owner) {
        return ApiResponse.success(environmentService.getUsageStatistics(owner));
    }
}
