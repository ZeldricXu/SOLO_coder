package com.streamsql.modules.cdc_capture;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.streamsql.common.ApiResponse;
import com.streamsql.common.PageResult;
import com.streamsql.dto.CdcTaskDTO;
import com.streamsql.entity.CdcCaptureTask;
import com.streamsql.entity.CdcEventRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/cdc")
@RequiredArgsConstructor
public class CdcCaptureController {

    private final CdcCaptureService cdcCaptureService;

    @PostMapping("/tasks")
    public Mono<ApiResponse<CdcCaptureTask>> createTask(@Validated @RequestBody CdcTaskDTO dto) throws JsonProcessingException {
        return Mono.just(ApiResponse.created(cdcCaptureService.createTask(dto)));
    }

    @DeleteMapping("/tasks/{taskId}")
    public Mono<ApiResponse<Void>> deleteTask(@PathVariable String taskId) {
        cdcCaptureService.deleteTask(taskId);
        return Mono.just(ApiResponse.success(null));
    }

    @GetMapping("/tasks/{taskId}")
    public Mono<ApiResponse<CdcCaptureTask>> getTask(@PathVariable String taskId) {
        return Mono.just(ApiResponse.success(cdcCaptureService.getTask(taskId)));
    }

    @GetMapping("/tasks")
    public Mono<ApiResponse<PageResult<CdcCaptureTask>>> listTasks(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String datasourceId,
            @RequestParam(required = false) String status) {
        return Mono.just(ApiResponse.success(cdcCaptureService.listTasks(page, size, datasourceId, status)));
    }

    @PostMapping("/tasks/{taskId}/start")
    public Mono<ApiResponse<CdcCaptureTask>> startTask(@PathVariable String taskId) throws JsonProcessingException {
        return Mono.just(ApiResponse.success(cdcCaptureService.startTask(taskId)));
    }

    @PostMapping("/tasks/{taskId}/stop")
    public Mono<ApiResponse<CdcCaptureTask>> stopTask(@PathVariable String taskId) {
        return Mono.just(ApiResponse.success(cdcCaptureService.stopTask(taskId)));
    }

    @GetMapping("/events")
    public Mono<ApiResponse<PageResult<CdcEventRecord>>> getEvents(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String taskId,
            @RequestParam(required = false) String eventType) {
        return Mono.just(ApiResponse.success(cdcCaptureService.getEventRecords(page, size, taskId, eventType)));
    }
}
