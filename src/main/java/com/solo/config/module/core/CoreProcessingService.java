package com.solo.config.module.core;

import com.solo.config.common.IdGenerator;
import com.solo.config.common.exception.BusinessException;
import com.solo.config.entity.RunInstance;
import com.solo.config.mapper.RunInstanceMapper;
import com.solo.config.module.config.ConfigService;
import com.solo.config.module.metrics.MetricsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CoreProcessingService {

    private final ConfigService configService;
    private final MetricsService metricsService;
    private final RunInstanceMapper runInstanceMapper;

    public Mono<Map<String, Object>> executeHandler(String namespace, Map<String, Object> params, Map<String, Object> payload) {
        RequestContext ctx = new RequestContext();
        ctx.setAttribute("namespace", namespace);
        ctx.setAttribute("params", params);

        return process(ctx, namespace, params, payload)
                .doOnSuccess(result -> {
                    recordMetrics(ctx, "success");
                    log.info("Request processed successfully, traceId: {}, elapsed: {}ms",
                            ctx.getTraceId(), ctx.getElapsedMs());
                })
                .doOnError(error -> {
                    recordMetrics(ctx, "error");
                    log.error("Request processing failed, traceId: {}, error: {}",
                            ctx.getTraceId(), error.getMessage());
                });
    }

    private Mono<Map<String, Object>> process(RequestContext ctx, String namespace,
                                              Map<String, Object> params, Map<String, Object> payload) {
        return validateParams(params)
                .flatMap(v -> loadConfig(ctx, namespace))
                .flatMap(config -> acquireResource(ctx, config))
                .flatMap(resource -> processCore(ctx, payload, (Map<String, Object>) ctx.getAttribute("config")))
                .flatMap(result -> persistResult(ctx, result))
                .flatMap(result -> emitEvent(ctx, result))
                .onErrorResume(this::handleException);
    }

    private Mono<Boolean> validateParams(Map<String, Object> params) {
        return Mono.fromCallable(() -> {
            if (params == null) {
                throw new BusinessException(400, "参数不能为空");
            }
            return true;
        });
    }

    private Mono<Map<String, Object>> loadConfig(RequestContext ctx, String namespace) {
        return configService.getConfig(namespace, "rules")
                .map(rules -> {
                    Map<String, Object> config = Map.of("rules", rules, "poolSize", 10);
                    ctx.setAttribute("config", config);
                    return config;
                })
                .switchIfEmpty(Mono.fromCallable(() -> {
                    Map<String, Object> config = Map.of("rules", "default", "poolSize", 10);
                    ctx.setAttribute("config", config);
                    return config;
                }));
    }

    private Mono<Object> acquireResource(RequestContext ctx, Map<String, Object> config) {
        return Mono.fromCallable(() -> {
            int poolSize = (Integer) config.getOrDefault("poolSize", 10);
            ctx.setAttribute("resource", "resource_" + poolSize);
            return ctx.getAttribute("resource");
        });
    }

    private Mono<Map<String, Object>> processCore(RequestContext ctx, Map<String, Object> payload, Map<String, Object> config) {
        return Mono.fromCallable(() -> {
            RunInstance runInstance = new RunInstance();
            runInstance.setRunId(IdGenerator.generateRunId());
            runInstance.setEntityId(ctx.getRequestId());
            runInstance.setPhase("processing");
            runInstance.setProgress(BigDecimal.ZERO);
            runInstance.setStartedAt(LocalDateTime.now());
            runInstanceMapper.insert(runInstance);

            ctx.setAttribute("runInstance", runInstance);

            Map<String, Object> result = new java.util.HashMap<>();
            result.put("runId", runInstance.getRunId());
            result.put("status", "completed");
            result.put("data", payload);
            result.put("processedAt", LocalDateTime.now().toString());

            runInstance.setPhase("finalizing");
            runInstance.setProgress(BigDecimal.ONE);
            runInstance.setCompletedAt(LocalDateTime.now());
            runInstanceMapper.updateById(runInstance);

            return result;
        });
    }

    private Mono<Map<String, Object>> persistResult(RequestContext ctx, Map<String, Object> result) {
        return Mono.fromCallable(() -> {
            ctx.setAttribute("persisted", true);
            return result;
        });
    }

    private Mono<Map<String, Object>> emitEvent(RequestContext ctx, Map<String, Object> result) {
        return Mono.fromCallable(() -> {
            log.info("Event emitted: task.completed, runId: {}", result.get("runId"));
            return result;
        });
    }

    private Mono<Map<String, Object>> handleException(Throwable error) {
        if (error instanceof BusinessException be) {
            return Mono.error(be);
        }
        return Mono.error(new BusinessException(500, "内部处理错误: " + error.getMessage()));
    }

    private void recordMetrics(RequestContext ctx, String status) {
        metricsService.incrementCounter("requests_total", "status:" + status);
        metricsService.recordTimer("request_duration_ms", ctx.getElapsedMs(), "status:" + status);
    }

    public Mono<RunInstance> getRunInstance(String runId) {
        return Mono.justOrEmpty(
                runInstanceMapper.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RunInstance>()
                                .eq("run_id", runId)
                )
        );
    }

    public reactor.core.publisher.Flux<RunInstance> listRunInstances(String entityId, String phase) {
        return reactor.core.publisher.Flux.fromIterable(
                runInstanceMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RunInstance>()
                                .eq(entityId != null, "entity_id", entityId)
                                .eq(phase != null, "phase", phase)
                                .orderByDesc("created_at")
                                .last("LIMIT 100")
                )
        );
    }
}
