package com.datastandard.modules.slo;

import com.datastandard.modules.slo.dto.BurnAlertResponse;
import com.datastandard.modules.slo.dto.ErrorBudgetResponse;
import com.datastandard.modules.slo.dto.SloDefinitionRequest;
import com.datastandard.modules.slo.entity.SloDefinition;
import com.datastandard.modules.slo.mapper.SloMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class BurnRateAlertService {

    private final SloMapper sloMapper;
    private final SloService sloService;
    private final ErrorBudgetService errorBudgetService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    private final Counter alertTriggeredCounter;
    private final Counter alertClearedCounter;
    private final Counter multiWindowAlertCounter;
    private final Map<String, Instant> activeAlerts = new ConcurrentHashMap<>();

    private static final long[] MULTI_WINDOW_SECONDS = {3600, 21600, 86400};

    public BurnRateAlertService(SloMapper sloMapper, SloService sloService,
                                ErrorBudgetService errorBudgetService,
                                ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.sloMapper = sloMapper;
        this.sloService = sloService;
        this.errorBudgetService = errorBudgetService;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;

        this.alertTriggeredCounter = Counter.builder("burn.rate.alert.triggered")
                .description("燃尽速率告警触发次数")
                .register(meterRegistry);
        this.alertClearedCounter = Counter.builder("burn.rate.alert.cleared")
                .description("燃尽速率告警清除次数")
                .register(meterRegistry);
        this.multiWindowAlertCounter = Counter.builder("burn.rate.alert.multiwindow")
                .description("多窗口告警触发次数")
                .register(meterRegistry);
    }

    public Flux<BurnAlertResponse> checkAlerts(String sloId) {
        return Mono.fromCallable(() -> {
            Timer.Sample sample = Timer.start(meterRegistry);
            try {
                return loadSloWithAlertThresholds(sloId);
            } finally {
                sample.stop(meterRegistry.timer("burn.rate.alert.check.duration"));
            }
        }).subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(sloWithThresholds -> checkAlertsWithBudget(sloWithThresholds));
    }

    public Flux<BurnAlertResponse> checkAllAlerts() {
        return Flux.fromIterable(() -> sloMapper.findAllEnabled().iterator())
                .flatMap(slo -> checkAlerts(slo.getSloId()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<BurnAlertResponse> triggerAlert(String sloId, String level, Double burnRate,
                                                 Double threshold, Duration windowDuration) {
        return Mono.fromCallable(() -> loadEnabledSlo(sloId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(slo -> buildAndTriggerAlert(slo, level, burnRate, threshold, windowDuration));
    }

    public Mono<BurnAlertResponse> clearAlert(String sloId, String level) {
        return Mono.fromCallable(() -> {
            SloDefinition slo = loadEnabledSlo(sloId);
            String alertKey = sloId + ":" + level;
            Instant alertTime = activeAlerts.remove(alertKey);

            if (alertTime != null) {
                alertClearedCounter.increment();
                log.info("告警清除: sloId={}, level={}", sloId, level);
            }

            return BurnAlertResponse.builder()
                    .alertId(UUID.randomUUID().toString())
                    .sloId(sloId)
                    .sloName(slo.getSloName())
                    .serviceName(slo.getServiceName())
                    .alertLevel(level)
                    .alertTime(Instant.now())
                    .alertStatus("CLEARED")
                    .severity(determineSeverity(level))
                    .description(String.format("SLO '%s' %s级别告警已清除", slo.getSloName(), level))
                    .build();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Flux<BurnAlertResponse> checkMultiWindowAlerts(String sloId) {
        return Mono.fromCallable(() -> loadEnabledSlo(sloId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(slo -> errorBudgetService.calculateErrorBudget(sloId)
                        .map(budget -> new SloWithBudget(slo, budget)))
                .flatMapMany(sloWithBudget -> evaluateMultiWindowAlerts(sloWithBudget));
    }

    private Flux<BurnAlertResponse> checkAlertsWithBudget(SloWithAlertThresholds sloWithThresholds) {
        return errorBudgetService.calculateErrorBudget(sloWithThresholds.slo().getSloId())
                .flatMapMany(budget -> evaluateThresholds(sloWithThresholds, budget));
    }

    private Flux<BurnAlertResponse> evaluateThresholds(SloWithAlertThresholds sloWithThresholds,
                                                        ErrorBudgetResponse budget) {
        List<BurnAlertResponse> alerts = new ArrayList<>();

        for (SloDefinitionRequest.AlertThreshold threshold : sloWithThresholds.thresholds()) {
            BurnAlertResponse alert = evaluateSingleThreshold(sloWithThresholds.slo(), threshold, budget);
            if (alert != null) {
                alerts.add(alert);
            }
        }

        if (alerts.size() > 1) {
            multiWindowAlertCounter.increment();
        }

        alerts.sort(Comparator.comparing(BurnAlertResponse::getSeverity)
                .thenComparing(BurnAlertResponse::getBurnRate).reversed());

        return Flux.fromIterable(alerts);
    }

    private BurnAlertResponse evaluateSingleThreshold(SloDefinition slo,
                                                       SloDefinitionRequest.AlertThreshold threshold,
                                                       ErrorBudgetResponse budget) {
        if (threshold.getBurnRateThreshold() == null || threshold.getWindowDuration() == null) {
            return null;
        }

        double windowBurnRate = calculateWindowBurnRate(slo.getSloId(), threshold.getWindowDuration().getSeconds());

        if (windowBurnRate > threshold.getBurnRateThreshold()) {
            return handleAlertTriggered(slo, threshold, budget, windowBurnRate);
        } else {
            clearAlertIfActive(slo.getSloId(), threshold.getLevel());
            return null;
        }
    }

    private BurnAlertResponse handleAlertTriggered(SloDefinition slo,
                                                    SloDefinitionRequest.AlertThreshold threshold,
                                                    ErrorBudgetResponse budget,
                                                    double windowBurnRate) {
        String alertKey = slo.getSloId() + ":" + threshold.getLevel();
        Instant now = Instant.now();

        BurnAlertResponse alert = buildAlertResponse(slo, threshold, budget, windowBurnRate, now);

        if (!activeAlerts.containsKey(alertKey)) {
            activeAlerts.put(alertKey, now);
            alertTriggeredCounter.increment();
            log.warn("告警触发: sloId={}, level={}, burnRate={}", slo.getSloId(), threshold.getLevel(), windowBurnRate);
            sendNotification(alert);
        } else {
            alert.setAlertStatus("ONGOING");
        }

        return alert;
    }

    private void clearAlertIfActive(String sloId, String level) {
        String alertKey = sloId + ":" + level;
        if (activeAlerts.containsKey(alertKey)) {
            activeAlerts.remove(alertKey);
            alertClearedCounter.increment();
            log.info("告警自动清除: sloId={}, level={}", sloId, level);
        }
    }

    private Mono<BurnAlertResponse> buildAndTriggerAlert(SloDefinition slo, String level,
                                                          Double burnRate, Double threshold,
                                                          Duration windowDuration) {
        return errorBudgetService.calculateErrorBudget(slo.getSloId())
                .map(budget -> {
                    Instant now = Instant.now();
                    String alertId = UUID.randomUUID().toString();

                    BurnAlertResponse alert = BurnAlertResponse.builder()
                            .alertId(alertId)
                            .sloId(slo.getSloId())
                            .sloName(slo.getSloName())
                            .serviceName(slo.getServiceName())
                            .alertLevel(level)
                            .burnRate(burnRate)
                            .threshold(threshold)
                            .remainingBudget(budget.getRemainingBudget())
                            .remainingBudgetPercent(budget.getRemainingBudgetPercent())
                            .windowDuration(windowDuration)
                            .windowStart(now.minus(windowDuration))
                            .windowEnd(now)
                            .alertTime(now)
                            .alertStatus("TRIGGERED")
                            .severity(determineSeverity(level))
                            .notifications(determineNotifications(level, slo))
                            .additionalInfo(Map.of(
                                    "budgetStatus", budget.getBudgetStatus(),
                                    "estimatedExhaustion", budget.getEstimatedExhaustionTime() != null ?
                                            budget.getEstimatedExhaustionTime().toString() : "N/A"
                            ))
                            .description(String.format("SLO '%s' 燃尽速率 %.2f 超过阈值 %.2f (窗口: %s)",
                                    slo.getSloName(), burnRate, threshold, formatDuration(windowDuration)))
                            .build();

                    String alertKey = slo.getSloId() + ":" + level;
                    if (!activeAlerts.containsKey(alertKey)) {
                        activeAlerts.put(alertKey, now);
                        alertTriggeredCounter.increment();
                        log.warn("告警触发: sloId={}, level={}, burnRate={}", slo.getSloId(), level, burnRate);
                        sendNotification(alert);
                    } else {
                        alert.setAlertStatus("ONGOING");
                    }

                    return alert;
                });
    }

    private Flux<BurnAlertResponse> evaluateMultiWindowAlerts(SloWithBudget sloWithBudget) {
        List<BurnAlertResponse> alerts = new ArrayList<>();

        for (long windowSeconds : MULTI_WINDOW_SECONDS) {
            double windowBurnRate = calculateWindowBurnRate(sloWithBudget.slo().getSloId(), windowSeconds);
            double threshold = determineThreshold(windowSeconds);

            if (windowBurnRate > threshold) {
                alerts.add(BurnAlertResponse.builder()
                        .alertId(UUID.randomUUID().toString())
                        .sloId(sloWithBudget.slo().getSloId())
                        .sloName(sloWithBudget.slo().getSloName())
                        .serviceName(sloWithBudget.slo().getServiceName())
                        .alertLevel(determineAlertLevel(windowBurnRate, threshold))
                        .burnRate(windowBurnRate)
                        .threshold(threshold)
                        .remainingBudget(sloWithBudget.budget().getRemainingBudget())
                        .remainingBudgetPercent(sloWithBudget.budget().getRemainingBudgetPercent())
                        .windowDuration(Duration.ofSeconds(windowSeconds))
                        .windowStart(Instant.now().minusSeconds(windowSeconds))
                        .windowEnd(Instant.now())
                        .alertTime(Instant.now())
                        .alertStatus("TRIGGERED")
                        .severity(determineSeverity(windowSeconds))
                        .additionalInfo(Map.of("windowType", "multiwindow"))
                        .description(String.format("多窗口告警: %s窗口燃尽速率 %.2f 超过阈值 %.2f",
                                formatDuration(Duration.ofSeconds(windowSeconds)), windowBurnRate, threshold))
                        .build());
            }
        }

        return Flux.fromIterable(alerts);
    }

    private BurnAlertResponse buildAlertResponse(SloDefinition slo,
                                                  SloDefinitionRequest.AlertThreshold threshold,
                                                  ErrorBudgetResponse budget,
                                                  double windowBurnRate,
                                                  Instant now) {
        return BurnAlertResponse.builder()
                .alertId(UUID.randomUUID().toString())
                .sloId(slo.getSloId())
                .sloName(slo.getSloName())
                .serviceName(slo.getServiceName())
                .alertLevel(threshold.getLevel())
                .burnRate(windowBurnRate)
                .threshold(threshold.getBurnRateThreshold())
                .remainingBudget(budget.getRemainingBudget())
                .remainingBudgetPercent(budget.getRemainingBudgetPercent())
                .windowDuration(threshold.getWindowDuration())
                .windowStart(now.minus(threshold.getWindowDuration()))
                .windowEnd(now)
                .alertTime(now)
                .alertStatus("TRIGGERED")
                .severity(determineSeverity(threshold.getLevel()))
                .notifications(List.of(threshold.getNotificationChannel()))
                .additionalInfo(Map.of("budgetStatus", budget.getBudgetStatus(), "sliType", slo.getSliType()))
                .description(String.format("SLO '%s' %s级别告警: 燃尽速率 %.2f 超过阈值 %.2f (窗口: %s)",
                        slo.getSloName(), threshold.getLevel(), windowBurnRate,
                        threshold.getBurnRateThreshold(), formatDuration(threshold.getWindowDuration())))
                .build();
    }

    private SloWithAlertThresholds loadSloWithAlertThresholds(String sloId) {
        SloDefinition slo = loadEnabledSlo(sloId);

        try {
            List<SloDefinitionRequest.AlertThreshold> thresholds =
                    objectMapper.readValue(slo.getAlertThresholds(),
                            new TypeReference<List<SloDefinitionRequest.AlertThreshold>>() {});

            if (thresholds == null || thresholds.isEmpty()) {
                return new SloWithAlertThresholds(slo, List.of());
            }

            return new SloWithAlertThresholds(slo, thresholds);
        } catch (Exception e) {
            log.error("解析告警阈值配置失败: sloId={}", sloId, e);
            return new SloWithAlertThresholds(slo, List.of());
        }
    }

    private SloDefinition loadEnabledSlo(String sloId) {
        SloDefinition slo = sloMapper.findById(sloId)
                .orElseThrow(() -> new IllegalArgumentException("SLO不存在: " + sloId));
        if (!slo.isEnabled()) {
            throw new IllegalStateException("SLO已禁用: " + sloId);
        }
        return slo;
    }

    private double calculateWindowBurnRate(String sloId, long windowSeconds) {
        try {
            Instant now = Instant.now();
            Instant windowStart = now.minusSeconds(windowSeconds);

            List<ErrorBudgetResponse> budgets = errorBudgetService
                    .calculateSlidingWindowBudget(sloId, windowSeconds, windowSeconds)
                    .collectList()
                    .block();

            if (budgets != null && !budgets.isEmpty()) {
                return budgets.get(budgets.size() - 1).getBurnRate();
            }

            ErrorBudgetResponse budget = errorBudgetService.calculateErrorBudget(sloId).block();
            return budget != null ? budget.getBurnRate() : 0;
        } catch (Exception e) {
            log.error("计算窗口燃尽速率失败: sloId={}, window={}", sloId, windowSeconds, e);
            return 0;
        }
    }

    private double determineThreshold(long windowSeconds) {
        if (windowSeconds <= 3600) {
            return 14.4;
        } else if (windowSeconds <= 21600) {
            return 6.0;
        } else {
            return 1.0;
        }
    }

    private String determineAlertLevel(double burnRate, double threshold) {
        double ratio = burnRate / threshold;
        if (ratio >= 3.0) {
            return "CRITICAL";
        } else if (ratio >= 2.0) {
            return "HIGH";
        } else if (ratio >= 1.5) {
            return "MEDIUM";
        } else {
            return "LOW";
        }
    }

    private String determineSeverity(String level) {
        return switch (level.toUpperCase()) {
            case "CRITICAL", "P1" -> "CRITICAL";
            case "HIGH", "P2" -> "HIGH";
            case "MEDIUM", "P3" -> "MEDIUM";
            case "LOW", "P4" -> "LOW";
            default -> "INFO";
        };
    }

    private String determineSeverity(long windowSeconds) {
        if (windowSeconds <= 3600) {
            return "CRITICAL";
        } else if (windowSeconds <= 21600) {
            return "HIGH";
        } else {
            return "MEDIUM";
        }
    }

    private List<String> determineNotifications(String level, SloDefinition slo) {
        return switch (level.toUpperCase()) {
            case "CRITICAL" -> List.of("email", "pager", "slack");
            case "HIGH" -> List.of("email", "slack");
            case "MEDIUM" -> List.of("slack");
            default -> List.of("email");
        };
    }

    private String formatDuration(Duration duration) {
        long hours = duration.toHours();
        if (hours >= 24) {
            return (hours / 24) + "天";
        } else if (hours > 0) {
            return hours + "小时";
        } else {
            return duration.toMinutes() + "分钟";
        }
    }

    private void sendNotification(BurnAlertResponse alert) {
        try {
            log.info("发送告警通知: alertId={}, slo={}, level={}, burnRate={}",
                    alert.getAlertId(), alert.getSloName(), alert.getAlertLevel(), alert.getBurnRate());
        } catch (Exception e) {
            log.error("发送告警通知失败: alertId={}", alert.getAlertId(), e);
        }
    }

    public Mono<Map<String, Instant>> getActiveAlerts() {
        return Mono.just(Map.copyOf(activeAlerts));
    }

    private record SloWithAlertThresholds(SloDefinition slo,
                                           List<SloDefinitionRequest.AlertThreshold> thresholds) {
    }

    private record SloWithBudget(SloDefinition slo, ErrorBudgetResponse budget) {
    }
}
