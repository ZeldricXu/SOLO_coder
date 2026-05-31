package com.didauth.core.engine;

import com.didauth.common.exception.BusinessException;
import com.didauth.core.context.RequestContext;
import com.didauth.core.context.RequestContextHolder;
import com.didauth.core.entity.SysConfig;
import com.didauth.core.mapper.SysConfigMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.function.Function;

@Slf4j
@Service
@RequiredArgsConstructor
public class CoreEngine {

    private final SysConfigMapper sysConfigMapper;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    public <T, R> Mono<R> execute(RequestContext ctx, T request,
                                  Function<T, Mono<R>> handler,
                                  String operationName) {
        log.debug("[{}] Executing operation: {}", ctx.getTraceId(), operationName);

        return Mono.just(request)
                .flatMap(handler)
                .contextWrite(context -> RequestContextHolder.set(context, ctx))
                .doOnSuccess(result -> {
                    ctx.setStatus("SUCCESS");
                    meterRegistry.counter("engine.execution.count", "operation", operationName, "status", "success")
                            .increment();
                    log.debug("[{}] Operation completed successfully: {}", ctx.getTraceId(), operationName);
                })
                .onErrorResume(e -> {
                    ctx.setStatus("ERROR");
                    ctx.setErrorMessage(e.getMessage());
                    meterRegistry.counter("engine.execution.count", "operation", operationName, "status", "error")
                            .increment();

                    if (e instanceof BusinessException) {
                        return Mono.error(e);
                    }

                    log.error("[{}] Operation failed: {}", ctx.getTraceId(), operationName, e);
                    return Mono.error(BusinessException.internalError("Operation failed: " + e.getMessage()));
                });
    }

    public Mono<Map<String, Object>> loadConfig(String namespace, String configId) {
        return Mono.fromCallable(() -> {
            SysConfig config = sysConfigMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysConfig>()
                            .eq(SysConfig::getConfigId, configId)
                            .eq(SysConfig::getNamespace, namespace)
                            .eq(SysConfig::getEnabled, true)
                            .orderByDesc(SysConfig::getVersion)
                            .last("LIMIT 1"));

            if (config == null) {
                throw BusinessException.notFound("Config not found: " + namespace + "/" + configId);
            }

            try {
                return objectMapper.readValue(config.getParameters(), Map.class);
            } catch (Exception e) {
                throw BusinessException.internalError("Failed to parse config");
            }
        });
    }

    public Mono<SysConfig> saveConfig(String namespace, String configId, Map<String, Object> parameters) {
        return Mono.fromCallable(() -> {
            try {
                SysConfig existing = sysConfigMapper.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysConfig>()
                                .eq(SysConfig::getConfigId, configId)
                                .eq(SysConfig::getNamespace, namespace)
                                .orderByDesc(SysConfig::getVersion)
                                .last("LIMIT 1"));

                SysConfig config = new SysConfig();
                config.setConfigId(configId);
                config.setNamespace(namespace);
                config.setVersion(existing != null ? existing.getVersion() + 1 : 1);
                config.setParameters(objectMapper.writeValueAsString(parameters));
                config.setEnabled(true);

                sysConfigMapper.insert(config);
                return config;
            } catch (Exception e) {
                throw BusinessException.internalError("Failed to save config: " + e.getMessage());
            }
        });
    }
}
