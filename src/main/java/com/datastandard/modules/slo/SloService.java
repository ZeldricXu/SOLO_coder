package com.datastandard.modules.slo;

import com.datastandard.modules.slo.dto.SloDefinitionRequest;
import com.datastandard.modules.slo.entity.SloDefinition;
import com.datastandard.modules.slo.mapper.SloMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class SloService {

    private final SloMapper sloMapper;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final MeterRegistry meterRegistry;

    private final Counter sloCreateCounter;
    private final Counter sloUpdateCounter;
    private final Counter sloDeleteCounter;
    private final Counter sloQueryCounter;

    public SloService(SloMapper sloMapper, ObjectMapper objectMapper, Validator validator, MeterRegistry meterRegistry) {
        this.sloMapper = sloMapper;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.meterRegistry = meterRegistry;

        this.sloCreateCounter = Counter.builder("slo.definition.create")
                .description("SLO定义创建次数")
                .register(meterRegistry);
        this.sloUpdateCounter = Counter.builder("slo.definition.update")
                .description("SLO定义更新次数")
                .register(meterRegistry);
        this.sloDeleteCounter = Counter.builder("slo.definition.delete")
                .description("SLO定义删除次数")
                .register(meterRegistry);
        this.sloQueryCounter = Counter.builder("slo.definition.query")
                .description("SLO定义查询次数")
                .register(meterRegistry);
    }

    public Mono<SloDefinition> createSlo(SloDefinitionRequest request) {
        return Mono.fromCallable(() -> {
            Timer.Sample sample = Timer.start(meterRegistry);
            try {
                var violations = validator.validate(request);
                if (!violations.isEmpty()) {
                    String errorMsg = violations.stream()
                            .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                            .reduce((a, b) -> a + "; " + b)
                            .orElse("参数校验失败");
                    throw new IllegalArgumentException("参数校验失败: " + errorMsg);
                }

                String sloId = UUID.randomUUID().toString();
                SloDefinition slo = SloDefinition.builder()
                        .sloId(sloId)
                        .sloName(request.getSloName())
                        .sloDescription(request.getSloDescription())
                        .serviceName(request.getServiceName())
                        .environment(request.getEnvironment())
                        .sliType(request.getSliType())
                        .targetValue(request.getTargetValue())
                        .targetDirection(request.getTargetDirection())
                        .timeWindowSeconds(request.getTimeWindow() != null ? request.getTimeWindow().getSeconds() : null)
                        .alertThresholds(request.getAlertThresholds() != null ?
                                objectMapper.writeValueAsString(request.getAlertThresholds()) : null)
                        .labels(request.getLabels() != null ?
                                objectMapper.writeValueAsString(request.getLabels()) : null)
                        .createdBy(request.getCreatedBy())
                        .enabled(request.isEnabled())
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .deleted(0)
                        .build();

                sloMapper.insert(slo);
                sloCreateCounter.increment();
                log.info("SLO定义创建成功: sloId={}, name={}", sloId, request.getSloName());
                return slo;
            } catch (JsonProcessingException e) {
                log.error("序列化SLO配置失败", e);
                throw new RuntimeException("序列化SLO配置失败: " + e.getMessage(), e);
            } finally {
                sample.stop(meterRegistry.timer("slo.definition.create.duration"));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<SloDefinition> updateSlo(String sloId, SloDefinitionRequest request) {
        return Mono.fromCallable(() -> {
            Timer.Sample sample = Timer.start(meterRegistry);
            try {
                SloDefinition existing = sloMapper.findById(sloId)
                        .orElseThrow(() -> new IllegalArgumentException("SLO不存在: " + sloId));

                var violations = validator.validate(request);
                if (!violations.isEmpty()) {
                    String errorMsg = violations.stream()
                            .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                            .reduce((a, b) -> a + "; " + b)
                            .orElse("参数校验失败");
                    throw new IllegalArgumentException("参数校验失败: " + errorMsg);
                }

                existing.setSloName(request.getSloName());
                existing.setSloDescription(request.getSloDescription());
                existing.setServiceName(request.getServiceName());
                existing.setEnvironment(request.getEnvironment());
                existing.setSliType(request.getSliType());
                existing.setTargetValue(request.getTargetValue());
                existing.setTargetDirection(request.getTargetDirection());
                existing.setTimeWindowSeconds(request.getTimeWindow() != null ? request.getTimeWindow().getSeconds() : null);
                existing.setAlertThresholds(request.getAlertThresholds() != null ?
                        objectMapper.writeValueAsString(request.getAlertThresholds()) : null);
                existing.setLabels(request.getLabels() != null ?
                        objectMapper.writeValueAsString(request.getLabels()) : null);
                existing.setEnabled(request.isEnabled());
                existing.setUpdatedAt(Instant.now());

                sloMapper.updateById(existing);
                sloUpdateCounter.increment();
                log.info("SLO定义更新成功: sloId={}", sloId);
                return existing;
            } catch (JsonProcessingException e) {
                log.error("序列化SLO配置失败", e);
                throw new RuntimeException("序列化SLO配置失败: " + e.getMessage(), e);
            } finally {
                sample.stop(meterRegistry.timer("slo.definition.update.duration"));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Void> deleteSlo(String sloId) {
        return Mono.fromRunnable(() -> {
            Timer.Sample sample = Timer.start(meterRegistry);
            try {
                SloDefinition existing = sloMapper.findById(sloId)
                        .orElseThrow(() -> new IllegalArgumentException("SLO不存在: " + sloId));
                existing.setDeleted(1);
                existing.setUpdatedAt(Instant.now());
                sloMapper.updateById(existing);
                sloDeleteCounter.increment();
                log.info("SLO定义删除成功: sloId={}", sloId);
            } finally {
                sample.stop(meterRegistry.timer("slo.definition.delete.duration"));
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    public Mono<SloDefinition> getSlo(String sloId) {
        return Mono.fromCallable(() -> {
            sloQueryCounter.increment();
            return sloMapper.findById(sloId)
                    .orElseThrow(() -> new IllegalArgumentException("SLO不存在: " + sloId));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Flux<SloDefinition> getSloByService(String serviceName) {
        return Flux.fromIterable(() -> {
            sloQueryCounter.increment();
            return sloMapper.findByServiceName(serviceName).iterator();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Flux<SloDefinition> getSloByEnvironment(String environment) {
        return Flux.fromIterable(() -> {
            sloQueryCounter.increment();
            return sloMapper.findByEnvironment(environment).iterator();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Flux<SloDefinition> getAllEnabledSlos() {
        return Flux.fromIterable(() -> {
            sloQueryCounter.increment();
            return sloMapper.findAllEnabled().iterator();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<List<SloDefinitionRequest.AlertThreshold>> getAlertThresholds(SloDefinition slo) {
        return Mono.fromCallable(() -> {
            if (slo.getAlertThresholds() == null) {
                return List.of();
            }
            return objectMapper.readValue(slo.getAlertThresholds(),
                    new TypeReference<List<SloDefinitionRequest.AlertThreshold>>() {});
        });
    }

    public Mono<Map<String, String>> getLabels(SloDefinition slo) {
        return Mono.fromCallable(() -> {
            if (slo.getLabels() == null) {
                return Map.of();
            }
            return objectMapper.readValue(slo.getLabels(),
                    new TypeReference<Map<String, String>>() {});
        });
    }
}
