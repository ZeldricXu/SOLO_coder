package com.datastandard.modules.anomaly;

import com.datastandard.common.model.AnomalyDetectionResult;
import com.datastandard.common.util.IdGenerator;
import com.datastandard.modules.anomaly.dto.AnomalyResult;
import com.datastandard.modules.anomaly.mapper.AnomalyDetectionResultMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
public class AnomalyResultProcessor {

    private final AnomalyDetectionResultMapper anomalyResultMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    private final Map<String, Long> lastAlertTimestamps = new ConcurrentHashMap<>();
    private final Map<String, Integer> alertCounts = new ConcurrentHashMap<>();

    private final Counter processedCounter;
    private final Counter alertTriggeredCounter;
    private final Counter deduplicatedCounter;
    private final Counter escalationCounter;

    private static final long ALERT_SUPPRESSION_WINDOW_MINUTES = 5;
    private static final int ESCALATION_THRESHOLD = 3;

    public AnomalyResultProcessor(AnomalyDetectionResultMapper anomalyResultMapper,
                                  ApplicationEventPublisher eventPublisher,
                                  ObjectMapper objectMapper,
                                  MeterRegistry meterRegistry) {
        this.anomalyResultMapper = anomalyResultMapper;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;

        this.processedCounter = Counter.builder("anomaly.result.processed.count")
                .description("已处理的异常结果数量")
                .register(meterRegistry);
        this.alertTriggeredCounter = Counter.builder("anomaly.alert.triggered.count")
                .description("触发的告警数量")
                .register(meterRegistry);
        this.deduplicatedCounter = Counter.builder("anomaly.alert.deduplicated.count")
                .description("去重的告警数量")
                .register(meterRegistry);
        this.escalationCounter = Counter.builder("anomaly.alert.escalation.count")
                .description("告警升级数量")
                .register(meterRegistry);
    }

    @EventListener
    @Async
    public void handleAnomalyDetectedEvent(AnomalyDetectionService.AnomalyDetectedEvent event) {
        log.debug("接收到异常检测事件: detectionCode={}, 异常数={}",
                event.getRequest().getDetectionCode(), event.getResults().size());

        Flux.fromIterable(event.getResults())
                .flatMap(result -> processResult(result, event.getRequest()))
                .onErrorContinue((e, r) -> log.error("处理异常结果失败: {}", r, e))
                .subscribe();
    }

    public Mono<AnomalyResult> processResult(AnomalyResult result, AnomalyDetectionRequest request) {
        return Mono.fromCallable(() -> {
            processedCounter.increment();

            String deduplicationKey = buildDeduplicationKey(result);

            if (shouldDeduplicate(deduplicationKey, result)) {
                deduplicatedCounter.increment();
                log.debug("告警已去重: {}", deduplicationKey);
                result.setAnalysisResult(mergeMap(result.getAnalysisResult(),
                        Map.of("deduplicated", true, "suppressedAt", LocalDateTime.now())));
                return result;
            }

            updateAlertState(deduplicationKey);

            if (shouldEscalate(deduplicationKey, result)) {
                escalationCounter.increment();
                result.setSeverity(escalateSeverity(result.getSeverity()));
                result.setAnalysisResult(mergeMap(result.getAnalysisResult(),
                        Map.of("escalated", true, "escalationCount", alertCounts.get(deduplicationKey))));
                log.info("告警已升级: key={}, 新级别={}", deduplicationKey, result.getSeverity());
            }

            AnomalyNotificationEvent notificationEvent = AnomalyNotificationEvent.builder()
                    .result(result)
                    .request(request)
                    .timestamp(LocalDateTime.now())
                    .notificationChannels(determineNotificationChannels(result))
                    .build();

            eventPublisher.publishEvent(notificationEvent);
            alertTriggeredCounter.increment();

            updateBaseline(result, request);

            log.info("异常结果处理完成: resultId={}, severity={}, metricCode={}",
                    result.getResultId(), result.getSeverity(), result.getMetricCode());

            return result;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Void> batchProcess(List<AnomalyResult> results, AnomalyDetectionRequest request) {
        return Flux.fromIterable(results)
                .flatMap(result -> processResult(result, request))
                .then()
                .doOnSuccess(v -> log.info("批量处理异常结果完成: 数量={}", results.size()))
                .doOnError(e -> log.error("批量处理异常结果失败", e));
    }

    public Mono<Void> acknowledgeAnomaly(Long resultId, String user) {
        return Mono.fromCallable(() -> {
            AnomalyDetectionResult result = anomalyResultMapper.selectById(resultId);
            if (result == null) {
                throw new IllegalArgumentException("异常结果不存在: " + resultId);
            }

            result.setStatus("ACKNOWLEDGED");
            result.setAcknowledgedBy(user);
            result.setAcknowledgedAt(LocalDateTime.now());
            anomalyResultMapper.updateById(result);

            String key = buildDeduplicationKeyFromEntity(result);
            lastAlertTimestamps.remove(key);
            alertCounts.remove(key);

            log.info("异常已确认: resultId={}, user={}", resultId, user);
            return null;
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    public Mono<Void> resolveAnomaly(Long resultId, String user) {
        return Mono.fromCallable(() -> {
            AnomalyDetectionResult result = anomalyResultMapper.selectById(resultId);
            if (result == null) {
                throw new IllegalArgumentException("异常结果不存在: " + resultId);
            }

            result.setStatus("RESOLVED");
            result.setAcknowledgedBy(user);
            result.setResolvedAt(LocalDateTime.now());
            anomalyResultMapper.updateById(result);

            log.info("异常已解决: resultId={}, user={}", resultId, user);
            return null;
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    public Mono<Map<String, Object>> getAnomalyStatistics(LocalDateTime startTime, LocalDateTime endTime) {
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> counts = anomalyResultMapper.countBySeverityAndTimeRange(startTime, endTime);

            long critical = 0, high = 0, medium = 0, low = 0;
            for (Map<String, Object> count : counts) {
                String severity = (String) count.get("severity");
                Long cnt = ((Number) count.get("count")).longValue();
                switch (severity) {
                    case "CRITICAL" -> critical = cnt;
                    case "HIGH" -> high = cnt;
                    case "MEDIUM" -> medium = cnt;
                    case "LOW" -> low = cnt;
                }
            }

            return Map.of(
                    "startTime", startTime,
                    "endTime", endTime,
                    "total", critical + high + medium + low,
                    "critical", critical,
                    "high", high,
                    "medium", medium,
                    "low", low,
                    "severityDistribution", Map.of(
                            "CRITICAL", critical,
                            "HIGH", high,
                            "MEDIUM", medium,
                            "LOW", low
                    )
            );
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private String buildDeduplicationKey(AnomalyResult result) {
        return result.getMetricCode() + ":" +
                (result.getEntityId() != null ? result.getEntityId() : "null") + ":" +
                (result.getInstanceId() != null ? result.getInstanceId() : "null") + ":" +
                result.getAnomalyType() + ":" +
                result.getSeverity();
    }

    private String buildDeduplicationKeyFromEntity(AnomalyDetectionResult result) {
        return result.getMetricCode() + ":" +
                (result.getEntityId() != null ? result.getEntityId() : "null") + ":" +
                (result.getInstanceId() != null ? result.getInstanceId() : "null") + ":" +
                result.getAnomalyType() + ":" +
                result.getSeverity();
    }

    private boolean shouldDeduplicate(String key, AnomalyResult result) {
        Long lastAlert = lastAlertTimestamps.get(key);
        if (lastAlert == null) return false;

        long elapsed = System.currentTimeMillis() - lastAlert;
        boolean suppress = elapsed < TimeUnit.MINUTES.toMillis(ALERT_SUPPRESSION_WINDOW_MINUTES);

        if (!suppress) {
            lastAlertTimestamps.put(key, System.currentTimeMillis());
        }

        return suppress;
    }

    private void updateAlertState(String key) {
        lastAlertTimestamps.put(key, System.currentTimeMillis());
        alertCounts.merge(key, 1, Integer::sum);
    }

    private boolean shouldEscalate(String key, AnomalyResult result) {
        Integer count = alertCounts.get(key);
        return count != null && count >= ESCALATION_THRESHOLD &&
                !"CRITICAL".equals(result.getSeverity());
    }

    private String escalateSeverity(String currentSeverity) {
        return switch (currentSeverity) {
            case "LOW" -> "MEDIUM";
            case "MEDIUM" -> "HIGH";
            case "HIGH" -> "CRITICAL";
            default -> currentSeverity;
        };
    }

    private List<String> determineNotificationChannels(AnomalyResult result) {
        List<String> channels = new ArrayList<>();
        channels.add("INTERNAL");

        switch (result.getSeverity()) {
            case "CRITICAL" -> {
                channels.add("EMAIL");
                channels.add("SMS");
                channels.add("VOICE");
            }
            case "HIGH" -> {
                channels.add("EMAIL");
                channels.add("SMS");
            }
            case "MEDIUM" -> channels.add("EMAIL");
            default -> {
            }
        }

        return channels;
    }

    private void updateBaseline(AnomalyResult result, AnomalyDetectionRequest request) {
        if (request.getDataPoints() != null && !request.getDataPoints().isEmpty()) {
            BigDecimal lastValue = request.getDataPoints().get(request.getDataPoints().size() - 1).getValue();
            log.debug("基线更新: metricCode={}, lastValue={}", request.getMetricCode(), lastValue);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeMap(Map<String, Object> map1, Map<String, Object> map2) {
        Map<String, Object> merged = new HashMap<>();
        if (map1 != null) merged.putAll(map1);
        if (map2 != null) merged.putAll(map2);
        return merged;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class AnomalyNotificationEvent {
        private AnomalyResult result;
        private AnomalyDetectionRequest request;
        private LocalDateTime timestamp;
        private List<String> notificationChannels;
    }
}
