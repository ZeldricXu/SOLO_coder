package com.nftindexer.modules.event.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nftindexer.common.ApiResponse;
import com.nftindexer.common.PageResult;
import com.nftindexer.entity.ContractEventListener;
import com.nftindexer.entity.ContractEventLog;
import com.nftindexer.modules.event.dto.EventListenerCreateRequest;
import com.nftindexer.modules.event.dto.EventProcessRequest;
import com.nftindexer.modules.event.service.ContractEventService;
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

    private final ContractEventService eventService;

    @PostMapping("/listeners")
    public Mono<ApiResponse<ContractEventListener>> createEventListener(
            @Valid @RequestBody EventListenerCreateRequest request) {
        return eventService.createEventListener(request)
                .map(listener -> ApiResponse.created(listener));
    }

    @GetMapping("/listeners/{listenerId}")
    public Mono<ApiResponse<ContractEventListener>> getEventListener(
            @PathVariable String listenerId) {
        return eventService.getEventListener(listenerId)
                .map(ApiResponse::success);
    }

    @GetMapping("/listeners")
    public Mono<ApiResponse<PageResult<ContractEventListener>>> listEventListeners(
            @RequestParam(required = false) String chainId,
            @RequestParam(required = false) String contractAddress,
            @RequestParam(required = false) String eventName,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return eventService.listEventListeners(chainId, contractAddress, eventName, status, pageNum, pageSize)
                .map(page -> ApiResponse.success(new PageResult<>(
                        page.getRecords(),
                        page.getTotal(),
                        page.getCurrent(),
                        page.getSize()
                )));
    }

    @PutMapping("/listeners/{listenerId}/status")
    public Mono<ApiResponse<ContractEventListener>> updateEventListenerStatus(
            @PathVariable String listenerId,
            @RequestBody Map<String, String> request) {
        String status = request.getOrDefault("status", "inactive");
        String updatedBy = request.getOrDefault("updatedBy", "system");
        return eventService.updateEventListenerStatus(listenerId, status, updatedBy)
                .map(ApiResponse::success);
    }

    @GetMapping("/listeners/{listenerId}/stats")
    public Mono<ApiResponse<Map<String, Object>>> getEventListenerStats(
            @PathVariable String listenerId) {
        return eventService.getEventListenerStats(listenerId)
                .map(ApiResponse::success);
    }

    @PostMapping("/process")
    public Mono<ApiResponse<ContractEventLog>> processEvent(
            @Valid @RequestBody EventProcessRequest request) {
        return eventService.processEvent(request)
                .map(eventLog -> ApiResponse.created(eventLog));
    }

    @GetMapping("/logs/{logId}")
    public Mono<ApiResponse<ContractEventLog>> getEventLog(@PathVariable String logId) {
        return eventService.getEventLog(logId)
                .map(ApiResponse::success);
    }

    @GetMapping("/logs")
    public Mono<ApiResponse<PageResult<ContractEventLog>>> listEventLogs(
            @RequestParam(required = false) String listenerId,
            @RequestParam(required = false) String chainId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String transactionHash,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return eventService.listEventLogs(listenerId, chainId, status, transactionHash, pageNum, pageSize)
                .map(page -> ApiResponse.success(new PageResult<>(
                        page.getRecords(),
                        page.getTotal(),
                        page.getCurrent(),
                        page.getSize()
                )));
    }
}
