package com.solocoder.dns.core.controller;

import com.solocoder.dns.common.model.ApiResponse;
import com.solocoder.dns.core.model.ProcessRequest;
import com.solocoder.dns.core.model.ProcessResult;
import com.solocoder.dns.core.service.CoreProcessService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/process")
@RequiredArgsConstructor
public class ProcessController {
    private final CoreProcessService coreProcessService;

    @PostMapping("/execute")
    public Mono<ApiResponse<ProcessResult>> execute(@RequestBody ProcessRequest request) {
        return coreProcessService.execute(request).map(ApiResponse::success);
    }

    @PostMapping("/resources")
    public ApiResponse<Map<String, Object>> createResource(@RequestBody Map<String, Object> request) {
        String id = "rsc_" + System.currentTimeMillis();
        return ApiResponse.success(201, Map.of(
                "id", id,
                "status", "provisioning"
        ));
    }

    @GetMapping("/resources/{id}/status")
    public ApiResponse<Map<String, Object>> getResourceStatus(@PathVariable String id) {
        return ApiResponse.success(Map.of(
                "id", id,
                "status", "running",
                "progress", 0.8
        ));
    }

    @PostMapping("/resources/batch")
    public ApiResponse<Map<String, Object>> batchOperation(@RequestBody Map<String, Object> request) {
        return ApiResponse.success(Map.of(
                "batchId", "batch_" + System.currentTimeMillis(),
                "results", java.util.List.of()
        ));
    }
}
