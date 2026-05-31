package com.chaoslab.modules.eventstore.controller;

import com.chaoslab.common.ApiResponse;
import com.chaoslab.entity.EventLog;
import com.chaoslab.entity.EventProjection;
import com.chaoslab.entity.EventSnapshot;
import com.chaoslab.modules.eventstore.dto.EventAppendRequest;
import com.chaoslab.modules.eventstore.dto.ProjectionCreateRequest;
import com.chaoslab.modules.eventstore.dto.TimeTravelQueryRequest;
import com.chaoslab.modules.eventstore.service.EventStoreService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventStoreController {

    private final EventStoreService eventStoreService;

    @PostMapping
    public Mono<ApiResponse<EventLog>> appendEvent(@Valid @RequestBody EventAppendRequest request) {
        return eventStoreService.appendEvent(request)
                .map(ApiResponse::success);
    }

    @GetMapping
    public Mono<ApiResponse<List<EventLog>>> getEvents(
            @RequestParam String aggregateId,
            @RequestParam(required = false) Long fromSequence,
            @RequestParam(required = false, defaultValue = "100") Integer limit) {
        return eventStoreService.getEvents(aggregateId, fromSequence, limit)
                .map(ApiResponse::success);
    }

    @GetMapping("/stream")
    public Flux<EventLog> streamEvents(
            @RequestParam String aggregateId,
            @RequestParam(required = false) Long fromSequence) {
        return eventStoreService.streamEvents(aggregateId, fromSequence);
    }

    @PostMapping("/snapshots")
    public Mono<ApiResponse<EventSnapshot>> createSnapshot(@RequestParam String aggregateId) {
        return eventStoreService.createSnapshot(aggregateId)
                .map(ApiResponse::success);
    }

    @GetMapping("/snapshots/latest")
    public Mono<ApiResponse<EventSnapshot>> getLatestSnapshot(@RequestParam String aggregateId) {
        return eventStoreService.getLatestSnapshot(aggregateId)
                .map(ApiResponse::success);
    }

    @PostMapping("/timetravel")
    public Mono<ApiResponse<Map<String, Object>>> timeTravelQuery(
            @Valid @RequestBody TimeTravelQueryRequest request) {
        return eventStoreService.timeTravelQuery(request)
                .map(ApiResponse::success);
    }

    @PostMapping("/projections")
    public Mono<ApiResponse<EventProjection>> createProjection(
            @Valid @RequestBody ProjectionCreateRequest request) {
        return eventStoreService.createProjection(request)
                .map(ApiResponse::success);
    }

    @PostMapping("/projections/{projectionId}/rebuild")
    public Mono<ApiResponse<EventProjection>> rebuildProjection(@PathVariable String projectionId) {
        return eventStoreService.rebuildProjection(projectionId)
                .map(ApiResponse::success);
    }

    @GetMapping("/projections")
    public Mono<ApiResponse<List<EventProjection>>> listProjections() {
        return eventStoreService.listProjections()
                .map(ApiResponse::success);
    }

    @GetMapping("/stats")
    public Mono<ApiResponse<Map<String, Object>>> getStats() {
        return eventStoreService.getStats()
                .map(ApiResponse::success);
    }
}
