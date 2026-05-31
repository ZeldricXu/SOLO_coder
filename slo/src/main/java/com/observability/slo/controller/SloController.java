package com.observability.slo.controller;

import com.observability.common.dto.ApiResponse;
import com.observability.slo.dto.SloCreateRequest;
import com.observability.slo.entity.SloConfigEntity;
import com.observability.slo.model.SloStatus;
import com.observability.slo.service.SloService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/slos")
@RequiredArgsConstructor
public class SloController {

    private final SloService sloService;

    @PostMapping
    public Mono<ApiResponse<SloConfigEntity>> createSlo(@RequestBody SloCreateRequest request) {
        return sloService.createSlo(
                request.getName(),
                request.getSliMetric(),
                request.getTarget(),
                request.getTimeWindow(),
                request.getBurnRateThreshold(),
                request.getNotificationConfig()
        ).map(ApiResponse::success);
    }

    @GetMapping
    public Mono<ApiResponse<List<SloConfigEntity>>> listSlos() {
        return sloService.listSlos()
                .map(ApiResponse::success);
    }

    @GetMapping("/{sloId}/status")
    public Mono<ApiResponse<SloStatus>> getSloStatus(@PathVariable String sloId) {
        return sloService.getSloStatus(sloId)
                .map(ApiResponse::success);
    }

    @GetMapping("/status")
    public Mono<ApiResponse<List<SloStatus>>> getAllSloStatus() {
        return sloService.getAllSloStatus()
                .map(ApiResponse::success);
    }

    @PostMapping("/{sloId}/record")
    public Mono<ApiResponse<String>> recordSliValue(
            @PathVariable String sloId,
            @RequestBody Map<String, Object> body) {
        double sliValue = ((Number) body.get("value")).doubleValue();
        boolean isGood = (Boolean) body.getOrDefault("isGood", true);
        return sloService.recordSliValue(sloId, sliValue, isGood)
                .then(Mono.just(ApiResponse.success("SLI value recorded successfully")));
    }

    @DeleteMapping("/{sloId}")
    public Mono<ApiResponse<String>> deleteSlo(@PathVariable String sloId) {
        return sloService.deleteSlo(sloId)
                .then(Mono.just(ApiResponse.success("SLO deleted successfully")));
    }
}
