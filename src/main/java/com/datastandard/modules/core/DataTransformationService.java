package com.datastandard.modules.core;

import com.datastandard.modules.core.dto.StandardizationConfig;
import com.datastandard.modules.core.dto.TransformRequest;
import com.datastandard.modules.core.dto.TransformResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DataTransformationService {

    private final MeterRegistry meterRegistry;
    private final DataTypeConverter dataTypeConverter;
    private final DataCleaningService dataCleaningService;
    private final ObjectProvider<List<StandardizationRule>> rulesProvider;
    private final ObjectMapper objectMapper;

    private final Counter transformSuccessCounter;
    private final Counter transformFailureCounter;
    private final Counter ruleExecutionCounter;
    private final Counter fallbackCounter;

    public DataTransformationService(MeterRegistry meterRegistry, DataTypeConverter dataTypeConverter,
                                     DataCleaningService dataCleaningService,
                                     ObjectProvider<List<StandardizationRule>> rulesProvider,
                                     ObjectMapper objectMapper) {
        this.meterRegistry = meterRegistry;
        this.dataTypeConverter = dataTypeConverter;
        this.dataCleaningService = dataCleaningService;
        this.rulesProvider = rulesProvider;
        this.objectMapper = objectMapper;

        this.transformSuccessCounter = Counter.builder("data.transform.success")
                .description("数据转换成功次数")
                .register(meterRegistry);
        this.transformFailureCounter = Counter.builder("data.transform.failure")
                .description("数据转换失败次数")
                .register(meterRegistry);
        this.ruleExecutionCounter = Counter.builder("data.rule.execution")
                .description("规则执行次数")
                .register(meterRegistry);
        this.fallbackCounter = Counter.builder("data.fallback.triggered")
                .description("降级逻辑触发次数")
                .register(meterRegistry);
    }

    public Mono<TransformResponse> transform(TransformRequest request) {
        String requestId = request.getRequestId() != null ? request.getRequestId() : UUID.randomUUID().toString();
        ProcessingContext context = new ProcessingContext(requestId, request.getDataSource(), request.getDatasetName());
        context.setConfig(request.getConfig());
        context.setTotalRecords(request.getRecords().size());

        Timer.Sample totalSample = Timer.start(meterRegistry);

        return loadConfigIfNeeded(request, context)
                .flatMap(config -> processRecords(request, context))
                .map(transformedRecords -> buildResponse(context, transformedRecords))
                .onErrorResume(e -> handleError(request, context, e))
                .doFinally(signalType -> {
                    context.complete();
                    totalSample.stop(meterRegistry.timer("data.transform.total.duration",
                            "dataSource", request.getDataSource(),
                            "dataset", request.getDatasetName()));
                });
    }

    private Mono<StandardizationConfig> loadConfigIfNeeded(TransformRequest request, ProcessingContext context) {
        if (request.getConfig() != null) {
            return Mono.just(request.getConfig());
        }
        return loadConfigFromTemplate(request.getDataSource(), request.getDatasetName())
                .doOnNext(config -> {
                    context.setConfig(config);
                    request.setConfig(config);
                })
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "未找到数据源 " + request.getDataSource() + " 的标准化配置")));
    }

    private Mono<StandardizationConfig> loadConfigFromTemplate(String dataSource, String datasetName) {
        return Mono.fromCallable(() -> {
            log.debug("从模板加载配置: dataSource={}, dataset={}", dataSource, datasetName);
            StandardizationConfig config = StandardizationConfig.builder()
                    .configId("default")
                    .configVersion("1.0")
                    .fieldRules(new ArrayList<>())
                    .enableDataCleaning(true)
                    .enableTypeConversion(true)
                    .enableValidation(true)
                    .failOnError(false)
                    .maxParallelism(4)
                    .timeoutMs(30000)
                    .build();
            return config;
        });
    }

    private Mono<List<Map<String, Object>>> processRecords(TransformRequest request, ProcessingContext context) {
        StandardizationConfig config = context.getConfig();
        int parallelism = config.getMaxParallelism() > 0 ? config.getMaxParallelism() : 4;
        long timeoutMs = config.getTimeoutMs() > 0 ? config.getTimeoutMs() : 30000;

        List<StandardizationRule> rules = rulesProvider.getIfAvailable(ArrayList::new);
        AtomicInteger index = new AtomicInteger(0);

        return Flux.fromIterable(request.getRecords())
                .parallel(parallelism)
                .runOn(Schedulers.parallel())
                .flatMap(record -> {
                    int recordIndex = index.getAndIncrement();
                    context.setCurrentRecordIndex(recordIndex);
                    return processSingleRecord(record, rules, config, context)
                            .timeout(Duration.ofMillis(timeoutMs),
                                    Mono.fromRunnable(() -> {
                                        context.addError(null, null, "TIMEOUT",
                                                "记录处理超时，超过 " + timeoutMs + "ms");
                                        context.incrementFailed();
                                    }))
                            .onErrorResume(e -> {
                                log.error("处理记录失败: index={}", recordIndex, e);
                                context.addError(null, null, "PROCESSING_ERROR",
                                        "记录处理失败: " + e.getMessage());
                                context.incrementFailed();
                                return Mono.just(record);
                            });
                })
                .sequential()
                .collectList()
                .doOnNext(records -> {
                    meterRegistry.gauge("data.transform.throughput",
                            tags(request.getDataSource(), request.getDatasetName()),
                            records.size());
                });
    }

    private Iterable<io.micrometer.core.instrument.Tag> tags(String dataSource, String datasetName) {
        return List.of(
                io.micrometer.core.instrument.Tag.of("dataSource", dataSource),
                io.micrometer.core.instrument.Tag.of("dataset", datasetName)
        );
    }

    private Mono<Map<String, Object>> processSingleRecord(Map<String, Object> record,
                                                          List<StandardizationRule> rules,
                                                          StandardizationConfig config,
                                                          ProcessingContext context) {
        Mono<Map<String, Object>> currentMono = Mono.just(record);

        if (config.isEnableDataCleaning()) {
            for (StandardizationConfig.FieldRule rule : config.getFieldRules()) {
                currentMono = currentMono.flatMap(r -> dataCleaningService.clean(r, rule, context));
            }
        }

        if (config.isEnableTypeConversion()) {
            for (StandardizationConfig.FieldRule rule : config.getFieldRules()) {
                if (rule.getTargetType() != null) {
                    currentMono = currentMono.flatMap(r -> dataTypeConverter.convert(r, rule, context));
                }
            }
        }

        for (StandardizationConfig.FieldRule fieldRule : config.getFieldRules()) {
            for (StandardizationRule rule : rules) {
                if (rule.supports(fieldRule)) {
                    ruleExecutionCounter.increment();
                    currentMono = currentMono.flatMap(r -> rule.apply(r, fieldRule, context));
                }
            }
        }

        return currentMono
                .doOnNext(r -> {
                    if (!context.hasErrors() || !config.isFailOnError()) {
                        context.incrementSuccess();
                        transformSuccessCounter.increment();
                    } else {
                        context.incrementFailed();
                        transformFailureCounter.increment();
                    }
                });
    }

    private TransformResponse buildResponse(ProcessingContext context, List<Map<String, Object>> transformedRecords) {
        TransformResponse response = TransformResponse.builder()
                .requestId(context.getRequestId())
                .status(context.hasErrors() ? "PARTIAL_SUCCESS" : "SUCCESS")
                .totalRecords(context.getTotalRecords())
                .successCount(context.getSuccessCount().get())
                .failedCount(context.getFailedCount().get())
                .transformedRecords(transformedRecords)
                .errors(context.getErrors())
                .startTime(context.getStartTime())
                .endTime(context.getEndTime())
                .durationMs(context.getDurationMs())
                .metrics(context.getMetrics())
                .build();

        if (context.isFallbackMode()) {
            response.setStatus("FALLBACK");
            fallbackCounter.increment();
        }

        return response;
    }

    private Mono<TransformResponse> handleError(TransformRequest request, ProcessingContext context, Throwable e) {
        log.error("数据转换发生严重错误: requestId={}", context.getRequestId(), e);

        if (shouldFallback(request, context, e)) {
            return executeFallback(request, context, e);
        }

        context.complete();
        TransformResponse response = TransformResponse.builder()
                .requestId(context.getRequestId())
                .status("FAILED")
                .totalRecords(context.getTotalRecords())
                .successCount(0)
                .failedCount(context.getTotalRecords())
                .transformedRecords(new ArrayList<>())
                .errors(List.of(TransformResponse.TransformError.builder()
                        .errorCode("FATAL_ERROR")
                        .errorMessage("处理失败: " + e.getMessage())
                        .build()))
                .startTime(context.getStartTime())
                .endTime(Instant.now())
                .durationMs(context.getDurationMs())
                .build();

        transformFailureCounter.increment(context.getTotalRecords());

        return Mono.just(response);
    }

    private boolean shouldFallback(TransformRequest request, ProcessingContext context, Throwable e) {
        StandardizationConfig config = request.getConfig();
        if (config == null) {
            return false;
        }
        Map<String, Object> customConfig = config.getCustomConfig();
        return customConfig != null && Boolean.TRUE.equals(customConfig.get("enableFallback"));
    }

    private Mono<TransformResponse> executeFallback(TransformRequest request, ProcessingContext context, Throwable e) {
        log.warn("执行降级逻辑: requestId={}, reason={}", context.getRequestId(), e.getMessage());
        context.enableFallback(e.getMessage());
        fallbackCounter.increment();

        return Mono.fromCallable(() -> {
            List<Map<String, Object>> fallbackRecords = request.getRecords().stream()
                    .map(record -> {
                        try {
                            String json = objectMapper.writeValueAsString(record);
                            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
                        } catch (Exception ex) {
                            return record;
                        }
                    })
                    .collect(Collectors.toList());

            context.complete();
            return TransformResponse.builder()
                    .requestId(context.getRequestId())
                    .status("FALLBACK")
                    .totalRecords(request.getRecords().size())
                    .successCount(fallbackRecords.size())
                    .failedCount(0)
                    .transformedRecords(fallbackRecords)
                    .errors(List.of(TransformResponse.TransformError.builder()
                            .errorCode("FALLBACK_TRIGGERED")
                            .errorMessage("已触发降级逻辑: " + e.getMessage())
                            .build()))
                    .startTime(context.getStartTime())
                    .endTime(context.getEndTime())
                    .durationMs(context.getDurationMs())
                    .metrics(Map.of("fallbackReason", e.getMessage()))
                    .build();
        });
    }
}
