package com.meshcontrol.eventstore.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.meshcontrol.common.response.ApiResponse;
import com.meshcontrol.common.response.PageResponse;
import com.meshcontrol.eventstore.dto.EventPublishRequest;
import com.meshcontrol.eventstore.dto.EventQueryRequest;
import com.meshcontrol.eventstore.dto.ProjectionRebuildRequest;
import com.meshcontrol.eventstore.dto.TimetravelQueryRequest;
import com.meshcontrol.eventstore.entity.EventLog;
import com.meshcontrol.eventstore.entity.Snapshot;
import com.meshcontrol.eventstore.service.EventStoreService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventStoreController {

    private final EventStoreService eventStoreService;

    @PostMapping
    public Mono<ApiResponse<EventLog>> publishEvent(@Valid @RequestBody EventPublishRequest request) {
        return Mono.just(ApiResponse.created(eventStoreService.publishEvent(request)));
    }

    @GetMapping
    public Mono<ApiResponse<PageResponse<EventLog>>> queryEvents(@ModelAttribute EventQueryRequest request) {
        IPage<EventLog> page = eventStoreService.queryEvents(request);
        return Mono.just(ApiResponse.success(PageResponse.of(page)));
    }

    @GetMapping("/stream/{aggregateType}/{aggregateId}")
    public Mono<ApiResponse<List<EventLog>>> getEventStream(
            @PathVariable String aggregateType,
            @PathVariable String aggregateId,
            @RequestParam(required = false) Integer sinceVersion) {
        return Mono.just(ApiResponse.success(
                eventStoreService.getEventStream(aggregateId, aggregateType, sinceVersion)));
    }

    @PostMapping("/snapshots/{aggregateType}/{aggregateId}")
    public Mono<ApiResponse<Snapshot>> createSnapshot(
            @PathVariable String aggregateType,
            @PathVariable String aggregateId) {
        return Mono.just(ApiResponse.created(
                eventStoreService.createSnapshot(aggregateId, aggregateType)));
    }

    @GetMapping("/snapshots/{aggregateType}/{aggregateId}/latest")
    public Mono<ApiResponse<Snapshot>> getLatestSnapshot(
            @PathVariable String aggregateType,
            @PathVariable String aggregateId) {
        return Mono.just(ApiResponse.success(
                eventStoreService.getLatestSnapshot(aggregateId, aggregateType)));
    }

    @GetMapping("/snapshots/{aggregateType}/{aggregateId}")
    public Mono<ApiResponse<List<Snapshot>>> getSnapshots(
            @PathVariable String aggregateType,
            @PathVariable String aggregateId) {
        return Mono.just(ApiResponse.success(
                eventStoreService.getSnapshots(aggregateId, aggregateType)));
    }

    @PostMapping("/timetravel")
    public Mono<ApiResponse<Map<String, Object>>> timeTravelQuery(
            @Valid @RequestBody TimetravelQueryRequest request) {
        return Mono.just(ApiResponse.success(eventStoreService.timeTravelQuery(request)));
    }

    @PostMapping("/projection/rebuild")
    public Mono<ApiResponse<Map<String, Object>>> rebuildProjection(
            @Valid @RequestBody ProjectionRebuildRequest request) {
        return Mono.just(ApiResponse.success(eventStoreService.rebuildProjection(request)));
    }
}
