package com.metricplatform.controller;

import com.metricplatform.common.ApiResponse;
import com.metricplatform.dto.DataLifecycleDTO;
import com.metricplatform.dto.LifecycleExecutionResult;
import com.metricplatform.entity.SysDataLifecycle;
import com.metricplatform.service.DataLifecycleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/lifecycles")
@RequiredArgsConstructor
public class DataLifecycleController {

    private final DataLifecycleService dataLifecycleService;

    @GetMapping
    public Mono<ApiResponse<List<SysDataLifecycle>>> getAllLifecycles() {
        return Mono.just(ApiResponse.success(dataLifecycleService.getAllLifecycles()));
    }

    @GetMapping("/{lifecycleId}")
    public Mono<ApiResponse<SysDataLifecycle>> getLifecycle(@PathVariable String lifecycleId) {
        SysDataLifecycle lifecycle = dataLifecycleService.getById(lifecycleId);
        if (lifecycle != null) {
            return Mono.just(ApiResponse.success(lifecycle));
        } else {
            return Mono.just(ApiResponse.notFound("生命周期配置不存在"));
        }
    }

    @PostMapping
    public Mono<ApiResponse<SysDataLifecycle>> createLifecycle(@Valid @RequestBody DataLifecycleDTO dto) {
        try {
            SysDataLifecycle lifecycle = dataLifecycleService.createLifecycle(dto);
            return Mono.just(ApiResponse.created(lifecycle));
        } catch (IllegalArgumentException e) {
            return Mono.just(ApiResponse.validationError(e.getMessage()));
        }
    }

    @PutMapping("/{lifecycleId}")
    public Mono<ApiResponse<SysDataLifecycle>> updateLifecycle(
            @PathVariable String lifecycleId,
            @Valid @RequestBody DataLifecycleDTO dto) {
        try {
            SysDataLifecycle lifecycle = dataLifecycleService.updateLifecycle(lifecycleId, dto);
            return Mono.just(ApiResponse.success(lifecycle));
        } catch (IllegalArgumentException e) {
            return Mono.just(ApiResponse.validationError(e.getMessage()));
        }
    }

    @DeleteMapping("/{lifecycleId}")
    public Mono<ApiResponse<Void>> deleteLifecycle(@PathVariable String lifecycleId) {
        boolean result = dataLifecycleService.deleteLifecycle(lifecycleId);
        if (result) {
            return Mono.just(ApiResponse.success(null));
        } else {
            return Mono.just(ApiResponse.notFound("生命周期配置不存在"));
        }
    }

    @PostMapping("/{lifecycleId}/execute")
    public Mono<ApiResponse<List<LifecycleExecutionResult>>> executeLifecycle(@PathVariable String lifecycleId) {
        SysDataLifecycle lifecycle = dataLifecycleService.getById(lifecycleId);
        if (lifecycle == null) {
            return Mono.just(ApiResponse.notFound("生命周期配置不存在"));
        }
        List<LifecycleExecutionResult> results = dataLifecycleService.processLifecycle(lifecycle);
        return Mono.just(ApiResponse.success(results));
    }

    @PostMapping("/execute-all")
    public Mono<ApiResponse<Map<String, Object>>> executeAllLifecycles() {
        List<SysDataLifecycle> configs = dataLifecycleService.getAllLifecycles();
        Map<String, Object> result = new HashMap<>();
        result.put("totalConfigs", configs.size());

        for (SysDataLifecycle config : configs) {
            dataLifecycleService.processLifecycleAsync(config);
        }

        result.put("message", "所有生命周期处理已异步启动");
        return Mono.just(ApiResponse.success(result));
    }

    @GetMapping("/{tableName}/preview")
    public Mono<ApiResponse<Map<String, Object>>> getPreview(
            @PathVariable String tableName,
            @RequestParam(defaultValue = "30") int days) {
        long affectedRows = dataLifecycleService.getAffectedRowsPreview(tableName, days);
        Map<String, Object> result = new HashMap<>();
        result.put("tableName", tableName);
        result.put("days", days);
        result.put("affectedRows", affectedRows);
        return Mono.just(ApiResponse.success(result));
    }
}
