package com.observability.scheduler.controller;

import com.observability.common.dto.ApiResponse;
import com.observability.scheduler.dto.JobCreateRequest;
import com.observability.scheduler.entity.ScheduledJobEntity;
import com.observability.scheduler.service.SchedulerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/v1/scheduler/jobs")
@RequiredArgsConstructor
public class SchedulerController {

    private final SchedulerService schedulerService;

    @PostMapping
    public Mono<ApiResponse<ScheduledJobEntity>> createJob(@RequestBody JobCreateRequest request) {
        return schedulerService.createJob(
                request.getName(),
                request.getCronExpression(),
                request.getJobType(),
                request.getJobParams()
        ).map(ApiResponse::success);
    }

    @GetMapping
    public Mono<ApiResponse<List<ScheduledJobEntity>>> listJobs() {
        return schedulerService.listJobs()
                .map(ApiResponse::success);
    }

    @GetMapping("/{jobId}")
    public Mono<ApiResponse<ScheduledJobEntity>> getJob(@PathVariable String jobId) {
        return schedulerService.getJob(jobId)
                .map(ApiResponse::success);
    }

    @PostMapping("/{jobId}/start")
    public Mono<ApiResponse<String>> startJob(@PathVariable String jobId) {
        return schedulerService.startJob(jobId)
                .then(Mono.just(ApiResponse.success("Job started successfully")));
    }

    @PostMapping("/{jobId}/stop")
    public Mono<ApiResponse<String>> stopJob(@PathVariable String jobId) {
        return schedulerService.stopJob(jobId)
                .then(Mono.just(ApiResponse.success("Job stopped successfully")));
    }

    @DeleteMapping("/{jobId}")
    public Mono<ApiResponse<String>> deleteJob(@PathVariable String jobId) {
        return schedulerService.deleteJob(jobId)
                .then(Mono.just(ApiResponse.success("Job deleted successfully")));
    }
}
