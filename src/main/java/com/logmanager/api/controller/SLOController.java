package com.logmanager.api.controller;

import com.logmanager.api.dto.SLODTO;
import com.logmanager.api.vo.ApiResponse;
import com.logmanager.domain.model.ErrorBudget;
import com.logmanager.domain.model.SLOConfig;
import com.logmanager.service.SLOService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/slos")
@RequiredArgsConstructor
public class SLOController {

    private final SLOService sloService;

    @PostMapping
    public Mono<ApiResponse<SLOConfig>> createSLO(@Valid @RequestBody SLODTO dto) {
        return sloService.createSLO(
                dto.getName(),
                dto.getServiceName(),
                dto.getTargetPercentage(),
                dto.getWindow(),
                dto.getSliConfig()
        ).map(ApiResponse::created);
    }

    @GetMapping("/{id}")
    public Mono<ApiResponse<SLOConfig>> getSLO(@PathVariable String id) {
        return sloService.getSLO(id)
                .map(ApiResponse::success)
                .defaultIfEmpty(ApiResponse.error(404, "SLO not found"));
    }

    @PutMapping("/{id}")
    public Mono<ApiResponse<SLOConfig>> updateSLO(@PathVariable String id, @RequestBody Map<String, Object> updates) {
        return sloService.updateSLO(id, updates)
                .map(ApiResponse::success);
    }

    @DeleteMapping("/{id}")
    public Mono<ApiResponse<Void>> deleteSLO(@PathVariable String id) {
        return sloService.deleteSLO(id)
                .then(Mono.just(ApiResponse.success(null)));
    }

    @GetMapping("/service/{serviceName}")
    public Mono<ApiResponse<Flux<SLOConfig>>> getSLOsByService(@PathVariable String serviceName) {
        return Mono.just(ApiResponse.success(sloService.getSLOsByService(serviceName)));
    }

    @GetMapping("/{id}/sli")
    public Mono<ApiResponse<Double>> calculateSLI(@PathVariable String id) {
        return sloService.calculateSLI(id)
                .map(ApiResponse::success);
    }

    @GetMapping("/{id}/error-budget")
    public Mono<ApiResponse<ErrorBudget>> getErrorBudget(@PathVariable String id) {
        return sloService.getErrorBudget(id)
                .map(ApiResponse::success)
                .defaultIfEmpty(ApiResponse.error(404, "Error budget not found"));
    }

    @PostMapping("/{id}/error-budget/consume")
    public Mono<ApiResponse<ErrorBudget>> consumeErrorBudget(@PathVariable String id, @RequestParam double amount) {
        return sloService.consumeErrorBudget(id, amount)
                .map(ApiResponse::success);
    }

    @GetMapping("/{id}/burn-rate-alert")
    public Mono<ApiResponse<Boolean>> checkBurnRateAlert(@PathVariable String id) {
        return sloService.checkBurnRateAlert(id)
                .map(ApiResponse::success);
    }

    @GetMapping("/{id}/error-budget/history")
    public Mono<ApiResponse<Flux<ErrorBudget>>> getErrorBudgetHistory(@PathVariable String id) {
        return Mono.just(ApiResponse.success(sloService.getErrorBudgetHistory(id)));
    }
}
