package com.solo.config.controller;

import com.solo.config.common.Result;
import com.solo.config.entity.Event;
import com.solo.config.entity.Snapshot;
import com.solo.config.module.event.EventStoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventController {

    private final EventStoreService eventStoreService;

    @PostMapping
    public Mono<Result<Event>> appendEvent(@RequestBody Map<String, Object> request) {
        String aggregateType = (String) request.get("aggregateType");
        String aggregateId = (String) request.get("aggregateId");
        String eventType = (String) request.get("eventType");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) request.get("payload");
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) request.get("metadata");

        return eventStoreService.appendEvent(aggregateType, aggregateId, eventType, payload, metadata)
                .map(Result::success);
    }

    @GetMapping
    public Flux<Event> listEvents(
            @RequestParam(required = false) String aggregateType,
            @RequestParam(required = false) String aggregateId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return eventStoreService.listEvents(aggregateType, aggregateId, page, size);
    }

    @GetMapping("/type/{eventType}")
    public Flux<Event> getEventsByType(@PathVariable String eventType) {
        return eventStoreService.getEventsByType(eventType);
    }

    @GetMapping("/aggregate/{aggregateType}/{aggregateId}")
    public Mono<Result<Map<String, Object>>> replayAggregate(
            @PathVariable String aggregateType,
            @PathVariable String aggregateId) {
        return eventStoreService.replayAggregate(aggregateType, aggregateId)
                .map(Result::success);
    }

    @GetMapping("/timetravel/{aggregateType}/{aggregateId}")
    public Mono<Result<Map<String, Object>>> timeTravelQuery(
            @PathVariable String aggregateType,
            @PathVariable String aggregateId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime timestamp) {
        return eventStoreService.timeTravelQuery(aggregateType, aggregateId, timestamp)
                .map(Result::success);
    }

    @GetMapping("/snapshots")
    public Flux<Snapshot> listSnapshots(
            @RequestParam(required = false) String aggregateType,
            @RequestParam(required = false) String aggregateId) {
        return eventStoreService.listSnapshots(aggregateType, aggregateId);
    }

    @GetMapping("/count")
    public Mono<Result<Map<String, Object>>> getEventCount(
            @RequestParam(required = false) String aggregateType,
            @RequestParam(required = false) String aggregateId) {
        return eventStoreService.getEventCount(aggregateType, aggregateId)
                .map(count -> Result.success(Map.of("count", count)));
    }
}
