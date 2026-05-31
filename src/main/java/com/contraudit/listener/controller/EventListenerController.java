package com.contraudit.listener.controller;

import com.contraudit.common.ApiResponse;
import com.contraudit.listener.entity.EventLog;
import com.contraudit.listener.entity.EventListener;
import com.contraudit.listener.service.EventListenerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventListenerController {

    private final EventListenerService listenerService;

    @PostMapping("/listeners")
    public Mono<ApiResponse<EventListener>> createListener(@Valid @RequestBody EventListener listener) {
        return Mono.just(ApiResponse.created(listenerService.createListener(listener)));
    }

    @GetMapping("/listeners/{id}")
    public Mono<ApiResponse<EventListener>> getListener(@PathVariable String id) {
        return Mono.just(ApiResponse.success(listenerService.getListener(id)));
    }

    @GetMapping("/listeners")
    public Mono<ApiResponse<List<EventListener>>> listListeners(
            @RequestParam(required = false) String chainType,
            @RequestParam(required = false) String contractAddress,
            @RequestParam(required = false) Integer status) {
        return Mono.just(ApiResponse.success(
                listenerService.listListeners(chainType, contractAddress, status)));
    }

    @PostMapping("/listeners/{id}/start")
    public Mono<ApiResponse<EventListener>> startListener(@PathVariable String id) {
        return Mono.just(ApiResponse.success(listenerService.startListener(id)));
    }

    @PostMapping("/listeners/{id}/stop")
    public Mono<ApiResponse<EventListener>> stopListener(@PathVariable String id) {
        return Mono.just(ApiResponse.success(listenerService.stopListener(id)));
    }

    @DeleteMapping("/listeners/{id}")
    public Mono<ApiResponse<Void>> deleteListener(@PathVariable String id) {
        listenerService.deleteListener(id);
        return Mono.just(ApiResponse.success(null));
    }

    @PostMapping("/logs")
    public Mono<ApiResponse<EventLog>> recordEvent(@RequestBody Map<String, Object> request) {
        EventLog log = listenerService.recordEvent(
                (String) request.get("listenerId"),
                (String) request.get("chainType"),
                (String) request.get("contractAddress"),
                (String) request.get("eventName"),
                (String) request.get("txHash"),
                request.get("blockNumber") != null ?
                        ((Number) request.get("blockNumber")).longValue() : null,
                (String) request.get("blockHash"),
                request.get("logIndex") != null ?
                        ((Number) request.get("logIndex")).intValue() : null,
                (String) request.get("eventData"),
                (String) request.get("decodedData")
        );
        return Mono.just(ApiResponse.created(log));
    }

    @PostMapping("/logs/{id}/process")
    public Mono<ApiResponse<EventLog>> processEvent(@PathVariable String id) {
        return Mono.just(ApiResponse.success(listenerService.processEvent(id)));
    }

    @PostMapping("/logs/{id}/callback")
    public Mono<ApiResponse<EventLog>> triggerCallback(@PathVariable String id) {
        return Mono.just(ApiResponse.success(listenerService.triggerCallback(id)));
    }

    @GetMapping("/logs/{id}")
    public Mono<ApiResponse<EventLog>> getEventLog(@PathVariable String id) {
        return Mono.just(ApiResponse.success(listenerService.getEventLog(id)));
    }

    @GetMapping("/logs")
    public Mono<ApiResponse<List<EventLog>>> listEventLogs(
            @RequestParam(required = false) String listenerId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long fromBlock,
            @RequestParam(required = false) Long toBlock,
            @RequestParam(required = false) String txHash) {
        return Mono.just(ApiResponse.success(
                listenerService.listEventLogs(listenerId, status, fromBlock, toBlock, txHash)));
    }

    @GetMapping("/listeners/{id}/status")
    public Mono<ApiResponse<Map<String, Object>>> getListenerStatus(@PathVariable String id) {
        EventListener listener = listenerService.getListener(id);
        boolean running = listenerService.isListenerRunning(id);
        return Mono.just(ApiResponse.success(Map.of(
                "listenerId", id,
                "name", listener.getListenerName(),
                "status", listener.getStatus() == 1 ? "RUNNING" : "STOPPED",
                "isRunning", running,
                "currentBlock", listener.getCurrentBlock(),
                "startBlock", listener.getStartBlock()
        )));
    }
}
