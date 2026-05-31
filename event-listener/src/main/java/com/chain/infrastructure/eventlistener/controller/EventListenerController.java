package com.chain.infrastructure.eventlistener.controller;

import com.chain.infrastructure.common.dto.ApiResponse;
import com.chain.infrastructure.eventlistener.dto.EventLog;
import com.chain.infrastructure.eventlistener.dto.EventSubscriptionRequest;
import com.chain.infrastructure.eventlistener.service.EventListenerService;
import com.chain.infrastructure.persistence.entity.ContractEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventListenerController {

    private final EventListenerService eventListenerService;

    @PostMapping("/subscribe")
    public Mono<ApiResponse<String>> subscribe(@RequestBody EventSubscriptionRequest request) {
        return eventListenerService.subscribe(request)
                .map(ApiResponse::created);
    }

    @PostMapping("/process")
    public Mono<ApiResponse<Void>> processEvent(@RequestBody EventLog eventLog) {
        return eventListenerService.processEvent(eventLog)
                .map(ApiResponse::success);
    }

    @PostMapping("/{eventId}/processed")
    public Mono<ApiResponse<Void>> markProcessed(@PathVariable String eventId) {
        return eventListenerService.markEventProcessed(eventId)
                .map(ApiResponse::success);
    }

    @GetMapping("/unprocessed/{chainType}")
    public Flux<ContractEvent> getUnprocessedEvents(@PathVariable String chainType) {
        return eventListenerService.getUnprocessedEvents(chainType);
    }

    @GetMapping("/contract/{chainType}/{contractAddress}")
    public Flux<ContractEvent> getEventsByContract(
            @PathVariable String chainType,
            @PathVariable String contractAddress) {
        return eventListenerService.getEventsByContract(chainType, contractAddress);
    }

    @GetMapping("/tx/{chainType}/{txHash}")
    public Flux<ContractEvent> getEventsByTxHash(
            @PathVariable String chainType,
            @PathVariable String txHash) {
        return eventListenerService.getEventsByTxHash(chainType, txHash);
    }
}
