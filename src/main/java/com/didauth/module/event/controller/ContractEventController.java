package com.didauth.module.event.controller;

import com.didauth.common.response.ApiResponse;
import com.didauth.core.entity.ContractEvent;
import com.didauth.core.entity.ContractEventLog;
import com.didauth.module.event.dto.RegisterEventRequest;
import com.didauth.module.event.service.ContractEventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class ContractEventController {

    private final ContractEventService contractEventService;

    @PostMapping("/register")
    public Mono<ApiResponse<String>> registerEventListener(@Valid @RequestBody RegisterEventRequest request) {
        return contractEventService.registerEventListener(request)
                .map(id -> ApiResponse.success(201, id));
    }

    @GetMapping("/listeners")
    public Mono<ApiResponse<List<ContractEvent>>> listEventListeners(
            @RequestParam(required = false) String chainType,
            @RequestParam(required = false) String contractAddress,
            @RequestParam(required = false) String userId) {
        return contractEventService.listEventListeners(chainType, contractAddress, userId)
                .map(ApiResponse::success);
    }

    @GetMapping("/listeners/{eventId}")
    public Mono<ApiResponse<ContractEvent>> getEventListener(@PathVariable String eventId) {
        return contractEventService.getEventListener(eventId)
                .map(ApiResponse::success);
    }

    @PostMapping("/listeners/{eventId}/toggle")
    public Mono<ApiResponse<Void>> toggleEventListener(
            @PathVariable String eventId,
            @RequestBody Map<String, Boolean> request) {
        return contractEventService.toggleEventListener(eventId, request.getOrDefault("active", true))
                .then(Mono.just(ApiResponse.success(null)));
    }

    @GetMapping("/listeners/{eventId}/logs")
    public Mono<ApiResponse<List<ContractEventLog>>> getEventLogs(
            @PathVariable String eventId,
            @RequestParam(defaultValue = "100") Integer limit) {
        return contractEventService.getEventLogs(eventId, limit)
                .map(ApiResponse::success);
    }

    @PostMapping("/emit")
    public Mono<ApiResponse<Void>> emitEvent(@RequestBody Map<String, Object> request) {
        return contractEventService.emitEvent(
                (String) request.get("chainType"),
                (String) request.get("contractAddress"),
                (String) request.get("txHash"),
                request.get("blockNumber") != null ? Long.valueOf(request.get("blockNumber").toString()) : 0,
                request.get("logIndex") != null ? Integer.valueOf(request.get("logIndex").toString()) : 0,
                (String) request.get("eventData"),
                (String) request.get("decodedData")
        ).then(Mono.just(ApiResponse.success(null)));
    }
}
