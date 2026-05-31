package com.iotplatform.edgeinference.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.iotplatform.common.dto.PageQuery;
import com.iotplatform.common.dto.PageResult;
import com.iotplatform.common.dto.Result;
import com.iotplatform.edgeinference.dto.InferenceTaskCreateDTO;
import com.iotplatform.edgeinference.dto.InferenceResultDTO;
import com.iotplatform.edgeinference.entity.InferenceTask;
import com.iotplatform.edgeinference.service.InferenceSchedulerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;

@RestController
@RequestMapping("/api/v1/inference")
@RequiredArgsConstructor
public class InferenceController {

    private final InferenceSchedulerService schedulerService;

    @PostMapping("/tasks")
    public Mono<Result<InferenceTask>> createTask(@Valid @RequestBody InferenceTaskCreateDTO dto) {
        return schedulerService.createTask(dto)
                .map(Result::success);
    }

    @GetMapping("/tasks/{taskId}")
    public Mono<Result<InferenceTask>> getTask(@PathVariable String taskId) {
        return schedulerService.getTask(taskId)
                .map(Result::success);
    }

    @GetMapping("/tasks")
    public Mono<Result<PageResult<InferenceTask>>> listTasks(
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) String modelId,
            @RequestParam(required = false) String status,
            @ModelAttribute PageQuery pageQuery) {
        return schedulerService.listTasks(deviceId, modelId, status,
                        pageQuery.getPageNum(), pageQuery.getPageSize())
                .map(page -> {
                    PageResult<InferenceTask> pageResult = new PageResult<>(
                            page.getRecords(),
                            page.getTotal(),
                            page.getPages(),
                            page.getCurrent(),
                            page.getSize()
                    );
                    return Result.success(pageResult);
                });
    }

    @PostMapping("/tasks/{taskId}/start")
    public Mono<Result<Void>> startTask(@PathVariable String taskId) {
        return schedulerService.startTask(taskId)
                .then(Mono.just(Result.success(null)));
    }

    @PostMapping("/tasks/{taskId}/progress")
    public Mono<Result<Void>> updateProgress(@PathVariable String taskId,
                                             @RequestParam double progress) {
        return schedulerService.updateProgress(taskId, progress)
                .then(Mono.just(Result.success(null)));
    }

    @PostMapping("/tasks/complete")
    public Mono<Result<Void>> completeTask(@Valid @RequestBody InferenceResultDTO result) {
        return schedulerService.completeTask(result)
                .then(Mono.just(Result.success(null)));
    }

    @PostMapping("/tasks/{taskId}/fail")
    public Mono<Result<Void>> failTask(@PathVariable String taskId,
                                        @RequestParam String errorDetail) {
        return schedulerService.failTask(taskId, errorDetail)
                .then(Mono.just(Result.success(null)));
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public Mono<Result<Void>> cancelTask(@PathVariable String taskId) {
        return schedulerService.cancelTask(taskId)
                .then(Mono.just(Result.success(null)));
    }

    @GetMapping("/tasks/pending")
    public Mono<Result<List<InferenceTask>>> getPendingTasks(@RequestParam(defaultValue = "10") int limit) {
        return schedulerService.getPendingTasks(limit)
                .map(Result::success);
    }

    @GetMapping("/devices/{deviceId}/tasks")
    public Mono<Result<List<InferenceTask>>> getDeviceTasks(@PathVariable String deviceId) {
        return schedulerService.getDeviceTasks(deviceId)
                .map(Result::success);
    }

    @PostMapping("/schedule")
    public Mono<Result<Void>> scheduleTasks() {
        return schedulerService.scheduleTasks()
                .then()
                .thenReturn(Result.success(null));
    }
}
