package com.monitoring.core.handler;

import com.monitoring.alert.scheduler.AlertEvaluationScheduler;
import com.monitoring.anomaly.algorithm.AnomalyDetector;
import com.monitoring.anomaly.service.AnomalyDetectionService;
import com.monitoring.common.context.ProcessingContext;
import com.monitoring.common.context.ContextHolder;
import com.monitoring.common.dto.*;
import com.monitoring.common.event.EventPublisher;
import com.monitoring.common.event.MonitoringEvent;
import com.monitoring.common.model.RunInstance;
import com.monitoring.common.utils.IdGenerator;
import com.monitoring.common.utils.JsonUtils;
import com.monitoring.common.utils.TimeUtils;
import com.monitoring.metrics.model.MetricPoint;
import com.monitoring.metrics.service.MetricsService;
import com.monitoring.storage.model.TimeSeriesPoint;
import com.monitoring.storage.service.TimeSeriesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class RequestHandler {

    private final AnomalyDetectionService anomalyDetectionService;
    private final MetricsService metricsService;
    private final TimeSeriesService timeSeriesService;
    private final EventPublisher eventPublisher;
    private final AlertEvaluationScheduler alertScheduler;

    private final Map<String, RunInstance> activeInstances = new ConcurrentHashMap<>();

    public Mono<ApiResponse<ResourceCreateResponse>> handleCreateResource(ResourceCreateRequest request) {
        String traceId = IdGenerator.generateTraceId();
        String requestId = IdGenerator.generateShortId();

        ProcessingContext ctx = ProcessingContext.builder()
                .traceId(traceId)
                .requestId(requestId)
                .startTime(Instant.now())
                .phase("init")
                .build();

        return Mono.just(request)
                .<ResourceCreateResponse>handle((req, sink) -> {
                    try {
                        validateParams(req);
                        ctx.setPhase("validating");

                        String resourceId = "rsc_" + IdGenerator.generateShortId();

                        RunInstance instance = RunInstance.builder()
                                .runId("run_" + IdGenerator.generateShortId())
                                .entityId(resourceId)
                                .phase("provisioning")
                                .progress(0.0)
                                .startedAt(Instant.now())
                                .build();
                        activeInstances.put(resourceId, instance);

                        var payload = req.getConfig() != null ? req.getConfig() : Map.of();
                        processPayload(payload, req.getType(), ctx);

                        var metrics = Map.<String, Object>of(
                                "resourceId", resourceId,
                                "type", req.getType(),
                                "durationMs", ctx.getElapsedMillis()
                        );

                        persistMetrics(resourceId, metrics);

                        emitEvent("resource.created", metrics, traceId);

                        alertScheduler.updateMetric("resource.created." + req.getType(), 1.0);

                        ResourceCreateResponse response = ResourceCreateResponse.builder()
                                .id(resourceId)
                                .status("provisioning")
                                .build();

                        sink.next(response);
                    } catch (Exception e) {
                        sink.error(e);
                    }
                })
                .map(response -> ApiResponse.success(201, response))
                .onErrorResume(e -> {
                    log.error("Error creating resource: {}", e.getMessage(), e);
                    ctx.setSuccess(false);
                    ctx.setErrorMessage(e.getMessage());
                    return Mono.just(ApiResponse.error(500, e.getMessage()));
                })
                .doFinally(signalType -> recordAndCleanup(ctx))
                .contextWrite(ContextHolder.withContext(ctx));
    }

    public Mono<ApiResponse<ResourceStatusResponse>> handleGetStatus(String id) {
        RunInstance instance = activeInstances.get(id);

        if (instance == null) {
            return Mono.just(ApiResponse.error(404, "Resource not found: " + id));
        }

        ResourceStatusResponse response = ResourceStatusResponse.builder()
                .id(instance.getEntityId())
                .status(instance.getPhase())
                .progress(instance.getProgress())
                .build();

        return Mono.just(ApiResponse.success(response));
    }

    public Mono<ApiResponse<BatchOperationResponse>> handleBatchOperation(BatchOperationRequest request) {
        String batchId = "batch_" + IdGenerator.generateShortId();

        return Mono.just(request)
                .flatMapMany(req -> reactor.core.publisher.Flux.fromIterable(req.getOperations()))
                .flatMap(op -> processOperation(op)
                        .onErrorResume(e -> Mono.just(BatchOperationResponse.OperationResult.builder()
                                .id(op.getId())
                                .action(op.getAction())
                                .success(false)
                                .message(e.getMessage())
                                .build())))
                .collectList()
                .map(results -> {
                    BatchOperationResponse response = BatchOperationResponse.builder()
                            .batchId(batchId)
                            .results(results)
                            .build();
                    return ApiResponse.success(response);
                });
    }

    private Mono<BatchOperationResponse.OperationResult> processOperation(BatchOperationRequest.BatchOperation op) {
        return Mono.fromSupplier(() -> {
            RunInstance instance = activeInstances.get(op.getId());
            if (instance == null) {
                return BatchOperationResponse.OperationResult.builder()
                        .id(op.getId())
                        .action(op.getAction())
                        .success(false)
                        .message("Resource not found")
                        .build();
            }

            switch (op.getAction().toLowerCase()) {
                case "stop" -> {
                    instance.setPhase("stopped");
                    instance.setCompletedAt(Instant.now());
                    activeInstances.remove(op.getId());
                }
                case "start" -> instance.setPhase("running");
                case "delete" -> activeInstances.remove(op.getId());
                default -> {
                    return BatchOperationResponse.OperationResult.builder()
                            .id(op.getId())
                            .action(op.getAction())
                            .success(false)
                            .message("Unsupported action: " + op.getAction())
                            .build();
                }
            }

            return BatchOperationResponse.OperationResult.builder()
                    .id(op.getId())
                    .action(op.getAction())
                    .success(true)
                    .message("Operation completed")
                    .build();
        });
    }

    private void validateParams(ResourceCreateRequest request) {
        if (request.getType() == null || request.getType().isBlank()) {
            throw new com.monitoring.common.exception.ValidationException("type is required");
        }
    }

    private void processPayload(Map<String, Object> payload, String type, ProcessingContext ctx) {
        ctx.setPhase("executing");

        if (!payload.isEmpty()) {
            double value = payload.values().stream()
                    .filter(v -> v instanceof Number)
                    .mapToDouble(v -> ((Number) v).doubleValue())
                    .sum();

            if (value > 0) {
                anomalyDetectionService.detect(type, value, "zscore", 3.0, 100, 0, Double.MAX_VALUE)
                        .subscribe(result -> {
                            if (result.isAnomaly()) {
                                log.warn("Anomaly detected during processing: {}", result.message());
                            }
                        });
            }
        }

        ctx.setPhase("completed");
        ctx.setSuccess(true);
    }

    private void persistMetrics(String resourceId, Map<String, Object> metrics) {
        TimeSeriesPoint point = TimeSeriesPoint.builder()
                .metric("resource.creation.duration")
                .value(((Number) metrics.get("durationMs")).doubleValue())
                .timestamp(System.currentTimeMillis())
                .tags(Map.of("resourceId", resourceId))
                .build();

        timeSeriesService.ingest(point).subscribe();

        MetricPoint metricPoint = MetricPoint.builder()
                .name("resource.created")
                .value(1.0)
                .dimensions(Map.of("type", (String) metrics.get("type")))
                .timestamp(Instant.now())
                .build();

        metricsService.recordMetric(metricPoint).subscribe();
    }

    private void emitEvent(String eventType, Map<String, Object> payload, String traceId) {
        MonitoringEvent event = MonitoringEvent.builder()
                .eventId(IdGenerator.generateShortId())
                .eventType(eventType)
                .source("core.handler")
                .payload(payload)
                .timestamp(Instant.now())
                .traceId(traceId)
                .build();

        eventPublisher.publish(event).subscribe();
    }

    private void recordAndCleanup(ProcessingContext ctx) {
        long elapsed = ctx.getElapsedMillis();

        MetricPoint metricPoint = MetricPoint.builder()
                .name("request.duration")
                .value((double) elapsed)
                .dimensions(Map.of(
                        "success", String.valueOf(ctx.getSuccess()),
                        "phase", ctx.getPhase()
                ))
                .timestamp(Instant.now())
                .build();

        metricsService.recordMetric(metricPoint).subscribe();

        ctx.cleanup();
    }

    public Mono<Map<String, Object>> getSystemStats() {
        return Mono.zip(
                metricsService.getMeterStats(),
                timeSeriesService.getStats(),
                (metricsStats, storageStats) -> {
                    Map<String, Object> stats = new java.util.HashMap<>();
                    stats.put("metrics", metricsStats);
                    stats.put("storage", storageStats);
                    stats.put("activeInstances", activeInstances.size());
                    return stats;
                }
        );
    }
}
