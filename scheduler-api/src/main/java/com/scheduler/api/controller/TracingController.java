package com.scheduler.api.controller;

import com.scheduler.common.model.ApiResponse;
import com.scheduler.persistence.entity.TraceSpan;
import com.scheduler.tracing.service.TraceCollectorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tracing")
@RequiredArgsConstructor
public class TracingController {

    private final TraceCollectorService traceCollectorService;

    @PostMapping("/spans")
    public Mono<ResponseEntity<ApiResponse<TraceSpan>>> collectSpan(@RequestBody TraceSpan span) {
        return traceCollectorService.collect(span)
                .map(s -> ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(s)));
    }

    @PostMapping("/spans/batch")
    public Flux<ResponseEntity<ApiResponse<TraceSpan>>> collectSpans(@RequestBody List<TraceSpan> spans) {
        return traceCollectorService.collectBatch(Flux.fromIterable(spans))
                .map(s -> ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(s)));
    }

    @GetMapping("/traces/{traceId}")
    public Mono<ResponseEntity<ApiResponse<List<TraceSpan>>>> getTrace(@PathVariable String traceId) {
        return Mono.fromCallable(() -> {
            List<TraceSpan> spans = traceCollectorService.getTrace(traceId);
            return ResponseEntity.ok(ApiResponse.success(spans));
        });
    }

    @GetMapping("/services")
    public Mono<ResponseEntity<ApiResponse<List<String>>>> getActiveServices(
            @RequestParam(defaultValue = "60") int sinceMinutes) {
        return Mono.fromCallable(() -> {
            List<String> services = traceCollectorService.getActiveServices(
                    Instant.now().minus(sinceMinutes, java.time.temporal.ChronoUnit.MINUTES));
            return ResponseEntity.ok(ApiResponse.success(services));
        });
    }

    @GetMapping("/sampling/strategies")
    public Mono<ResponseEntity<ApiResponse<List<String>>>> getSamplingStrategies() {
        return Mono.fromCallable(() ->
                ResponseEntity.ok(ApiResponse.success(traceCollectorService.getSamplingStrategies()))
        );
    }

    @PostMapping("/sampling/{strategy}/config")
    public Mono<ResponseEntity<ApiResponse<Void>>> updateSamplerConfig(
            @PathVariable String strategy,
            @RequestBody Map<String, Object> config) {
        return Mono.fromCallable(() -> {
            traceCollectorService.updateSamplerConfig(strategy, config);
            return ResponseEntity.ok(ApiResponse.success(null));
        });
    }
}
