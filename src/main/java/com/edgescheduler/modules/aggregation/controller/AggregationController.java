package com.edgescheduler.modules.aggregation.controller;

import com.edgescheduler.common.Result;
import com.edgescheduler.modules.aggregation.domain.DataAggregation;
import com.edgescheduler.modules.aggregation.service.DataAggregationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/aggregation")
@RequiredArgsConstructor
public class AggregationController {

    private final DataAggregationService dataAggregationService;

    @PostMapping("/data")
    public Mono<Result<DataAggregation>> aggregateData(
            @RequestParam String deviceId,
            @RequestParam String aggregationType,
            @RequestParam(defaultValue = "5m") String timeWindow,
            @RequestBody Map<String, Object> dataPoint) {
        return dataAggregationService.aggregateData(deviceId, aggregationType, timeWindow, dataPoint)
                .map(Result::success);
    }

    @PostMapping("/force")
    public Mono<Result<DataAggregation>> forceAggregation(
            @RequestParam String deviceId,
            @RequestParam String aggregationType,
            @RequestParam(defaultValue = "5m") String timeWindow) {
        return dataAggregationService.forceAggregation(deviceId, aggregationType, timeWindow)
                .map(Result::success);
    }

    @GetMapping("/pending")
    public Flux<Result<DataAggregation>> getPendingUploads(
            @RequestParam(required = false) String deviceId) {
        return dataAggregationService.getPendingUploads(deviceId)
                .map(Result::success);
    }

    @PutMapping("/{aggregationId}/uploaded")
    public Mono<Result<DataAggregation>> markAsUploaded(@PathVariable String aggregationId) {
        return dataAggregationService.markAsUploaded(aggregationId)
                .map(Result::success);
    }

    @GetMapping("/buffer-status")
    public Mono<Result<Map<String, Object>>> getBufferStatus() {
        return dataAggregationService.getBufferStatus()
                .map(Result::success);
    }

    @PostMapping("/recovery")
    public Mono<Result<Map<String, Object>>> attemptRecovery() {
        return dataAggregationService.attemptRecovery()
                .map(Result::success);
    }

    @PostMapping("/{aggregationId}/retry")
    public Mono<Result<DataAggregation>> retryFailedAggregation(@PathVariable String aggregationId) {
        return dataAggregationService.retryFailedAggregation(aggregationId)
                .map(Result::success);
    }

    @GetMapping("/dlq")
    public Mono<Result<List<Map<String, Object>>>> getDeadLetterQueue(
            @RequestParam(required = false) String type) {
        return dataAggregationService.getDeadLetterQueue(type)
                .map(Result::success);
    }

    @DeleteMapping("/dlq")
    public Mono<Result<Boolean>> clearDeadLetterQueue(
            @RequestParam(required = false) String type) {
        return dataAggregationService.clearDeadLetterQueue(type)
                .map(Result::success);
    }

    @GetMapping("/failed")
    public Flux<Result<DataAggregation>> getFailedAggregations(
            @RequestParam(required = false) String deviceId) {
        return dataAggregationService.getFailedAggregations(deviceId)
                .map(Result::success);
    }

    @GetMapping("/recovery-status")
    public Mono<Result<Map<String, Object>>> getRecoveryStatus() {
        return dataAggregationService.getRecoveryStatus()
                .map(Result::success);
    }
}
