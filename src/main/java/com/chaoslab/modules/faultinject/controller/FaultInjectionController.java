package com.chaoslab.modules.faultinject.controller;

import com.chaoslab.common.ApiResponse;
import com.chaoslab.entity.FaultInjectionRun;
import com.chaoslab.entity.FaultScenario;
import com.chaoslab.modules.faultinject.dto.FaultInjectRequest;
import com.chaoslab.modules.faultinject.dto.FaultInjectionStatusResponse;
import com.chaoslab.modules.faultinject.dto.FaultScenarioCreateRequest;
import com.chaoslab.modules.faultinject.service.FaultInjectionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/fault")
@RequiredArgsConstructor
public class FaultInjectionController {

    private final FaultInjectionService faultInjectionService;

    @PostMapping("/scenarios")
    public Mono<ApiResponse<FaultScenario>> createScenario(
            @Valid @RequestBody FaultScenarioCreateRequest request) {
        return faultInjectionService.createScenario(request)
                .map(ApiResponse::success);
    }

    @GetMapping("/scenarios")
    public Mono<ApiResponse<List<FaultScenario>>> listScenarios(
            @RequestParam(required = false) String faultType,
            @RequestParam(required = false) Boolean enabled) {
        return faultInjectionService.listScenarios(faultType, enabled)
                .map(ApiResponse::success);
    }

    @GetMapping("/scenarios/{scenarioId}")
    public Mono<ApiResponse<FaultScenario>> getScenario(@PathVariable String scenarioId) {
        return faultInjectionService.getScenario(scenarioId)
                .map(ApiResponse::success);
    }

    @PostMapping("/inject")
    public Mono<ApiResponse<FaultInjectionRun>> startInjection(
            @Valid @RequestBody FaultInjectRequest request) {
        return faultInjectionService.startInjection(request)
                .map(ApiResponse::success);
    }

    @PostMapping("/runs/{runId}/rollback")
    public Mono<ApiResponse<FaultInjectionRun>> triggerRollback(
            @PathVariable String runId,
            @RequestBody(required = false) Map<String, String> request) {
        String reason = request != null ? request.getOrDefault("reason", "Manual rollback") : "Manual rollback";
        return faultInjectionService.triggerRollback(runId, reason)
                .map(ApiResponse::success);
    }

    @PostMapping("/runs/{runId}/stop")
    public Mono<ApiResponse<FaultInjectionRun>> stopInjection(@PathVariable String runId) {
        return faultInjectionService.stopInjection(runId)
                .map(ApiResponse::success);
    }

    @GetMapping("/runs/{runId}/status")
    public Mono<ApiResponse<FaultInjectionStatusResponse>> getInjectionStatus(@PathVariable String runId) {
        return faultInjectionService.getInjectionStatus(runId)
                .map(ApiResponse::success);
    }

    @GetMapping("/runs/active")
    public Mono<ApiResponse<List<FaultInjectionRun>>> listActiveRuns() {
        return faultInjectionService.listActiveRuns()
                .map(ApiResponse::success);
    }
}
