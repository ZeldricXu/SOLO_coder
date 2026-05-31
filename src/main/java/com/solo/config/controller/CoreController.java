package com.solo.config.controller;

import com.solo.config.common.Result;
import com.solo.config.dto.ExecuteRequest;
import com.solo.config.entity.RunInstance;
import com.solo.config.module.core.CoreProcessingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CoreController {

    private final CoreProcessingService coreProcessingService;

    @PostMapping("/execute")
    public Mono<Result<Map<String, Object>>> execute(@Valid @RequestBody ExecuteRequest request) {
        return coreProcessingService.executeHandler(
                        request.getNamespace(),
                        request.getParams(),
                        request.getPayload() != null ? request.getPayload() : request.getParams()
                )
                .map(Result::success);
    }

    @GetMapping("/runs/{runId}")
    public Mono<Result<RunInstance>> getRunInstance(@PathVariable String runId) {
        return coreProcessingService.getRunInstance(runId)
                .map(Result::success)
                .defaultIfEmpty(Result.error(404, "运行实例不存在"));
    }

    @GetMapping("/runs")
    public Flux<RunInstance> listRunInstances(
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String phase) {
        return coreProcessingService.listRunInstances(entityId, phase);
    }
}
