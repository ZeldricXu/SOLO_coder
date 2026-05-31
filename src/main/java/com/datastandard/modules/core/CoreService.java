package com.datastandard.modules.core;

import com.datastandard.modules.core.config.ConfigLoader;
import com.datastandard.modules.core.dto.StandardizationConfig;
import com.datastandard.modules.core.dto.TransformRequest;
import com.datastandard.modules.core.dto.TransformResponse;
import com.datastandard.modules.core.entity.ProcessingLog;
import com.datastandard.modules.core.mapper.ProcessingLogMapper;
import com.datastandard.modules.core.resource.ResourcePoolManager;
import com.datastandard.modules.core.validator.RequestValidator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class CoreService {

    private final DataTransformationService dataTransformationService;
    private final ProcessingLogMapper processingLogMapper;
    private final ObjectMapper objectMapper;
    private final TransactionalOperator transactionalOperator;
    private final RequestValidator requestValidator;
    private final ConfigLoader configLoader;
    private final ResourcePoolManager resourcePoolManager;
    private final MeterRegistry meterRegistry;

    private final Counter requestTotalCounter;
    private final Counter requestSuccessCounter;
    private final Counter requestFailedCounter;
    private final Counter fallbackTriggeredCounter;

    public CoreService(DataTransformationService dataTransformationService,
                       ProcessingLogMapper processingLogMapper,
                       ObjectMapper objectMapper,
                       TransactionalOperator transactionalOperator,
                       RequestValidator requestValidator,
                       ConfigLoader configLoader,
                       ResourcePoolManager resourcePoolManager,
                       MeterRegistry meterRegistry) {
        this.dataTransformationService = dataTransformationService;
        this.processingLogMapper = processingLogMapper;
        this.objectMapper = objectMapper;
        this.transactionalOperator = transactionalOperator;
        this.requestValidator = requestValidator;
        this.configLoader = configLoader;
        this.resourcePoolManager = resourcePoolManager;
        this.meterRegistry = meterRegistry;

        this.requestTotalCounter = Counter.builder("core.request.total")
                .description("总请求数")
                .register(meterRegistry);
        this.requestSuccessCounter = Counter.builder("core.request.success")
                .description("成功请求数")
                .register(meterRegistry);
        this.requestFailedCounter = Counter.builder("core.request.failed")
                .description("失败请求数")
                .register(meterRegistry);
        this.fallbackTriggeredCounter = Counter.builder("core.fallback.triggered")
                .description("降级触发次数")
                .register(meterRegistry);
    }

    public Mono<TransformResponse> executeHandler(TransformRequest request) {
        initializeRequest(request);
        requestTotalCounter.increment();

        Timer.Sample totalSample = Timer.start(meterRegistry);

        return Mono.fromCallable(() -> {
                    requestValidator.validate(request);
                    return request;
                })
                .flatMap(this::executeWithResource)
                .doOnError(e -> handleError(request.getRequestId(), e))
                .doOnSuccess(response -> handleSuccess(request, response))
                .doFinally(signalType -> totalSample.stop(meterRegistry.timer("core.handler.duration",
                        "dataSource", request.getDataSource(),
                        "dataset", request.getDatasetName())));
    }

    private void initializeRequest(TransformRequest request) {
        if (request.getRequestId() == null) {
            request.setRequestId(UUID.randomUUID().toString());
        }
        if (request.getTimestamp() == null) {
            request.setTimestamp(Instant.now());
        }
    }

    private Mono<TransformResponse> executeWithResource(TransformRequest request) {
        return Mono.fromCallable(() -> resourcePoolManager.tryAcquire())
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(acquired -> {
                    if (!acquired) {
                        return executeFallback(request, "资源池已满，触发降级");
                    }
                    return executeWithConfig(request)
                            .doFinally(signalType -> resourcePoolManager.release());
                });
    }

    private Mono<TransformResponse> executeWithConfig(TransformRequest request) {
        return Mono.fromCallable(() -> configLoader.loadConfiguration(request))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(config -> {
                    request.setConfig(config);
                    return processWithTransaction(request);
                });
    }

    private Mono<TransformResponse> processWithTransaction(TransformRequest request) {
        return dataTransformationService.transform(request)
                .as(transactionalOperator::transactional)
                .onErrorResume(e -> {
                    log.error("事务处理失败，尝试降级: requestId={}", request.getRequestId(), e);
                    return executeFallback(request, "事务处理失败: " + e.getMessage());
                });
    }

    private Mono<TransformResponse> executeFallback(TransformRequest request, String reason) {
        log.warn("执行降级处理: requestId={}, reason={}", request.getRequestId(), reason);
        fallbackTriggeredCounter.increment();

        return Mono.fromCallable(() -> buildFallbackResponse(request, reason))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private TransformResponse buildFallbackResponse(TransformRequest request, String reason) {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("fallbackReason", reason);
        metrics.put("fallbackTime", Instant.now().toString());

        return TransformResponse.builder()
                .requestId(request.getRequestId())
                .status("FALLBACK")
                .totalRecords(request.getRecords().size())
                .successCount(request.getRecords().size())
                .failedCount(0)
                .transformedRecords(request.getRecords())
                .errors(java.util.List.of(TransformResponse.TransformError.builder()
                        .errorCode("FALLBACK")
                        .errorMessage("降级处理: " + reason)
                        .build()))
                .startTime(Instant.now())
                .endTime(Instant.now())
                .durationMs(0)
                .metrics(metrics)
                .build();
    }

    private void handleError(String requestId, Throwable e) {
        log.error("executeHandler执行失败: requestId={}", requestId, e);
        requestFailedCounter.increment();
    }

    private void handleSuccess(TransformRequest request, TransformResponse response) {
        updateCounters(response);
        saveProcessingLog(request, response)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    private void updateCounters(TransformResponse response) {
        String status = response.getStatus();
        if ("SUCCESS".equals(status) || "PARTIAL_SUCCESS".equals(status)) {
            requestSuccessCounter.increment();
        } else if ("FALLBACK".equals(status)) {
            fallbackTriggeredCounter.increment();
        } else {
            requestFailedCounter.increment();
        }
    }

    private Mono<Void> saveProcessingLog(TransformRequest request, TransformResponse response, String requestId) {
        return Mono.fromRunnable(() -> persistLog(request, response))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    private void persistLog(TransformRequest request, TransformResponse response) {
        try {
            ProcessingLog logEntry = buildLogEntry(request, response);
            processingLogMapper.insert(logEntry);
            log.debug("处理日志已保存: requestId={}", request.getRequestId());
        } catch (JsonProcessingException e) {
            log.error("保存处理日志失败: requestId={}", request.getRequestId(), e);
        }
    }

    private ProcessingLog buildLogEntry(TransformRequest request, TransformResponse response) throws JsonProcessingException {
        return ProcessingLog.builder()
                .logId(UUID.randomUUID().toString())
                .requestId(request.getRequestId())
                .dataSource(request.getDataSource())
                .datasetName(request.getDatasetName())
                .templateId(request.getConfig() != null ? request.getConfig().getConfigId() : null)
                .status(response.getStatus())
                .totalRecords(response.getTotalRecords())
                .successCount(response.getSuccessCount())
                .failedCount(response.getFailedCount())
                .durationMs(response.getDurationMs())
                .errorMessage(response.getErrors() != null && !response.getErrors().isEmpty()
                        ? objectMapper.writeValueAsString(response.getErrors())
                        : null)
                .metrics(response.getMetrics() != null
                        ? objectMapper.writeValueAsString(response.getMetrics())
                        : null)
                .startTime(response.getStartTime())
                .endTime(response.getEndTime())
                .createdAt(Instant.now())
                .deleted(0)
                .build();
    }
}
