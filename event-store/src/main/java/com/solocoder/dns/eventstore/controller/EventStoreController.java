package com.solocoder.dns.eventstore.controller;

import com.solocoder.dns.common.entity.DomainEvent;
import com.solocoder.dns.common.model.ApiResponse;
import com.solocoder.dns.common.model.PageResult;
import com.solocoder.dns.eventstore.model.EventQuery;
import com.solocoder.dns.eventstore.model.Snapshot;
import com.solocoder.dns.eventstore.service.EventStoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventStoreController {
    private final EventStoreService eventStoreService;

    @PostMapping
    public ApiResponse<DomainEvent> appendEvent(@RequestBody DomainEvent event) {
        return ApiResponse.success(201, eventStoreService.appendEvent(event));
    }

    @GetMapping("/aggregate/{aggregateId}")
    public ApiResponse<List<DomainEvent>> loadEvents(@PathVariable String aggregateId) {
        return ApiResponse.success(eventStoreService.loadEvents(aggregateId));
    }

    @GetMapping("/aggregate/{aggregateId}/range")
    public ApiResponse<List<DomainEvent>> loadEventsRange(
            @PathVariable String aggregateId,
            @RequestParam Long from,
            @RequestParam Long to) {
        return ApiResponse.success(eventStoreService.loadEvents(aggregateId, from, to));
    }

    @GetMapping("/aggregate/{aggregateId}/type/{eventType}")
    public ApiResponse<List<DomainEvent>> loadEventsByType(
            @PathVariable String aggregateId,
            @PathVariable String eventType) {
        return ApiResponse.success(eventStoreService.loadEventsByType(aggregateId, eventType));
    }

    @PostMapping("/snapshots")
    public ApiResponse<Snapshot> createSnapshot(@RequestBody Map<String, Object> request) {
        return ApiResponse.success(201, eventStoreService.createSnapshot(
                (String) request.get("aggregateId"),
                request.get("state")));
    }

    @GetMapping("/snapshots/{aggregateId}/latest")
    public ApiResponse<Snapshot> getLatestSnapshot(@PathVariable String aggregateId) {
        return ApiResponse.success(eventStoreService.getLatestSnapshot(aggregateId));
    }

    @GetMapping("/aggregate/{aggregateId}/reconstruct")
    public ApiResponse<Object> reconstructState(@PathVariable String aggregateId) {
        return ApiResponse.success(eventStoreService.reconstructState(aggregateId));
    }

    @GetMapping("/aggregate/{aggregateId}/timetravel")
    public ApiResponse<List<DomainEvent>> timeTravel(
            @PathVariable String aggregateId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime pointInTime) {
        return ApiResponse.success(eventStoreService.timeTravelQuery(aggregateId, pointInTime));
    }

    @PostMapping("/query")
    public ApiResponse<PageResult<DomainEvent>> queryEvents(
            @RequestBody EventQuery query,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(eventStoreService.queryEvents(query, page, size));
    }
}
