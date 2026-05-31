package com.chainetl.modules.events.controller;

import com.chainetl.common.dto.ApiResponse;
import com.chainetl.modules.events.dto.CreateListenerRequest;
import com.chainetl.modules.events.dto.EventLogResponse;
import com.chainetl.modules.events.dto.ListenerResponse;
import com.chainetl.modules.events.dto.ProcessEventRequest;
import com.chainetl.modules.events.service.ContractEventListenerService;
import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class ContractEventListenerController {

    private final ContractEventListenerService eventListenerService;

    @PostMapping("/listeners")
    @Timed(value = "events.listener.create", description = "Time taken to create event listener")
    public Mono<ResponseEntity<ApiResponse<ListenerResponse>>> createListener(
            @Valid @RequestBody CreateListenerRequest request) {
        return eventListenerService.createListener(request)
                .map(listener -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(ApiResponse.success(201, listener)));
    }

    @PostMapping("/listeners/{listenerId}/start")
    @Timed(value = "events.listener.start", description = "Time taken to start event listener")
    public Mono<ResponseEntity<ApiResponse<ListenerResponse>>> startListener(
            @PathVariable String listenerId) {
        return eventListenerService.startListener(listenerId)
                .map(listener -> ResponseEntity.ok(ApiResponse.success(listener)));
    }

    @PostMapping("/listeners/{listenerId}/stop")
    @Timed(value = "events.listener.stop", description = "Time taken to stop event listener")
    public Mono<ResponseEntity<ApiResponse<ListenerResponse>>> stopListener(
            @PathVariable String listenerId) {
        return eventListenerService.stopListener(listenerId)
                .map(listener -> ResponseEntity.ok(ApiResponse.success(listener)));
    }

    @DeleteMapping("/listeners/{listenerId}")
    @Timed(value = "events.listener.delete", description = "Time taken to delete event listener")
    public Mono<ResponseEntity<ApiResponse<Void>>> deleteListener(
            @PathVariable String listenerId) {
        return eventListenerService.deleteListener(listenerId)
                .then(Mono.just(ResponseEntity.ok(ApiResponse.success(null))));
    }

    @GetMapping("/listeners/{listenerId}")
    @Timed(value = "events.listener.get", description = "Time taken to get event listener")
    public Mono<ResponseEntity<ApiResponse<ListenerResponse>>> getListener(
            @PathVariable String listenerId) {
        return eventListenerService.getListener(listenerId)
                .map(listener -> ResponseEntity.ok(ApiResponse.success(listener)));
    }

    @GetMapping("/listeners")
    @Timed(value = "events.listener.list", description = "Time taken to list event listeners")
    public Mono<ResponseEntity<ApiResponse<List<ListenerResponse>>>> listListeners(
            @RequestParam(required = false) String chainId,
            @RequestParam(required = false) String status) {
        return eventListenerService.listListeners(chainId, status)
                .map(listeners -> ResponseEntity.ok(ApiResponse.success(listeners)));
    }

    @GetMapping("/logs")
    @Timed(value = "events.logs.get", description = "Time taken to get event logs")
    public Mono<ResponseEntity<ApiResponse<List<EventLogResponse>>>> getEventLogs(
            @RequestParam(required = false) String chainId,
            @RequestParam(required = false) String contractAddress,
            @RequestParam(required = false) Boolean processed,
            @RequestParam(required = false) Long fromBlock,
            @RequestParam(required = false) Long toBlock) {
        return eventListenerService.getEventLogs(chainId, contractAddress, processed, fromBlock, toBlock)
                .map(logs -> ResponseEntity.ok(ApiResponse.success(logs)));
    }

    @PostMapping("/logs/process")
    @Timed(value = "events.logs.process.batch", description = "Time taken to process event logs batch")
    public Mono<ResponseEntity<ApiResponse<Integer>>> processEventLogs() {
        return eventListenerService.processEventLogs()
                .map(count -> ResponseEntity.ok(ApiResponse.success(count)));
    }

    @PostMapping("/logs/process/single")
    @Timed(value = "events.log.process.single", description = "Time taken to process single event log")
    public Mono<ResponseEntity<ApiResponse<EventLogResponse>>> processSingleEvent(
            @Valid @RequestBody ProcessEventRequest request) {
        return eventListenerService.processSingleEvent(request)
                .map(log -> ResponseEntity.ok(ApiResponse.success(log)));
    }
}
