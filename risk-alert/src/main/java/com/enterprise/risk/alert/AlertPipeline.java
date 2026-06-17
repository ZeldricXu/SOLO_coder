package com.enterprise.risk.alert;

import com.enterprise.risk.common.alert.AlertEvent;
import com.enterprise.risk.common.alert.AlertSeverity;
import com.enterprise.risk.common.rule.RuleDefinition;
import com.enterprise.risk.common.rule.RuleEvaluationResult;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class AlertPipeline {

    private final AlertFingerprintGenerator fingerprintGenerator;
    private final SlidingWindowDeduplicator deduplicator;
    private final AlertAggregator aggregator;
    private final AlertEscalationService escalationService;
    private final AlertSuppressionService suppressionService;
    private final AlertConvergenceManager convergenceManager;

    private final Map<String, RuleDefinition> ruleDefinitionMap = new ConcurrentHashMap<>();

    private final List<PipelineListener> listeners = new ArrayList<>();

    @PostConstruct
    public void init() {
        convergenceManager.setWindowCloseCallback(this::onWindowClosed);
        convergenceManager.start();
        log.info("告警流水线初始化完成");
    }

    @PreDestroy
    public void destroy() {
        convergenceManager.shutdown();
        log.info("告警流水线已销毁");
    }

    public PipelineResult process(List<RuleEvaluationResult> results) {
        PipelineResult pipelineResult = new PipelineResult();

        if (CollectionUtils.isEmpty(results)) {
            log.debug("告警流水线无待处理结果");
            return pipelineResult;
        }

        List<RuleEvaluationResult> matchedResults = results.stream()
                .filter(r -> r != null && r.isMatched())
                .collect(Collectors.toList());

        if (matchedResults.isEmpty()) {
            log.debug("告警流水线无匹配的规则结果");
            return pipelineResult;
        }

        log.info("告警流水线开始处理: 共 {} 条匹配结果", matchedResults.size());

        for (RuleEvaluationResult result : matchedResults) {
            try {
                processSingleResult(result, pipelineResult);
            } catch (Exception e) {
                log.error("处理规则结果异常: ruleId={}", result.getRuleId(), e);
                pipelineResult.recordError(result.getRuleId(), e);
            }
        }

        log.info("告警流水线处理完成: 输出={}, 去重={}, 被抑制={}, 错误={}",
                pipelineResult.getOutputAlerts().size(),
                pipelineResult.getDeduplicatedCount(),
                pipelineResult.getSuppressedCount(),
                pipelineResult.getErrorCount());

        firePipelineComplete(pipelineResult);
        return pipelineResult;
    }

    private void processSingleResult(RuleEvaluationResult result,
                                     PipelineResult pipelineResult) {
        String ruleId = result.getRuleId();
        RuleDefinition ruleDef = ruleDefinitionMap.get(ruleId);

        AlertSeverity severity = resolveSeverity(result, ruleDef);
        String description = buildDescription(result, ruleDef);
        Map<String, Object> dynamicFields = buildDynamicFields(result);

        String fingerprint = fingerprintGenerator.generate(
                result, severity, dynamicFields);

        notifyBeforeDedup(fingerprint, result);

        SlidingWindowDeduplicator.DeduplicationResult dedupResult =
                deduplicator.checkAndRecord(fingerprint).orElse(null);

        if (dedupResult != null && dedupResult.isDuplicated()) {
            pipelineResult.incrementDeduplicated();
            pipelineResult.recordDeduplicated(fingerprint, dedupResult.getHitCount());
            notifyAfterDedup(fingerprint, true, dedupResult.getExistingAlertId());

            if (dedupResult.getExistingAlertId() != null) {
                AlertEvent aggregated = aggregator.aggregate(
                        fingerprint, result, severity, description);
                convergenceManager.record(fingerprint, aggregated, null);
                escalationService.checkAndEscalate(aggregated);
            }
            return;
        }
        notifyAfterDedup(fingerprint, false, null);

        AlertEvent alert = aggregator.aggregate(fingerprint, result, severity, description);

        deduplicator.bindAlertId(fingerprint, alert.getAlertId());

        notifyBeforeEscalation(alert);
        boolean escalated = escalationService.checkAndEscalate(alert);
        notifyAfterEscalation(alert, escalated);

        notifyBeforeSuppression(alert);
        boolean suppressed = suppressionService.checkSuppressed(alert);
        notifyAfterSuppression(alert, suppressed);

        if (suppressed) {
            pipelineResult.incrementSuppressed();
            pipelineResult.recordSuppressed(fingerprint, alert.getSuppressedBy());
            return;
        }

        suppressionService.recordSuppression(alert);

        convergenceManager.record(fingerprint, alert, null);

        pipelineResult.addOutputAlert(alert);
        notifyAlertProduced(alert);
    }

    private AlertSeverity resolveSeverity(RuleEvaluationResult result,
                                          RuleDefinition ruleDef) {
        AlertSeverity severity = ruleDef != null ? ruleDef.getSeverity() : null;
        if (severity == null) {
            Double finalScore = result.getFinalScore() != null
                    ? result.getFinalScore()
                    : (result.getRuleScore() != null ? result.getRuleScore() : 0.0);
            severity = mapScoreToSeverity(finalScore);
        }
        return severity;
    }

    private AlertSeverity mapScoreToSeverity(double score) {
        if (score >= 0.9) {
            return AlertSeverity.CRITICAL;
        } else if (score >= 0.7) {
            return AlertSeverity.HIGH;
        } else if (score >= 0.5) {
            return AlertSeverity.MEDIUM;
        } else if (score >= 0.3) {
            return AlertSeverity.WARNING;
        }
        return AlertSeverity.INFO;
    }

    private String buildDescription(RuleEvaluationResult result,
                                    RuleDefinition ruleDef) {
        if (ruleDef != null && ruleDef.getDescription() != null) {
            return ruleDef.getDescription();
        }
        if (!CollectionUtils.isEmpty(result.getMatchedReasons())) {
            return String.join("; ", result.getMatchedReasons());
        }
        return String.format("规则命中: %s",
                result.getRuleName() != null ? result.getRuleName() : result.getRuleId());
    }

    private Map<String, Object> buildDynamicFields(RuleEvaluationResult result) {
        Map<String, Object> fields = new HashMap<>();
        if (result.getContext() != null) {
            fields.putAll(result.getContext());
        }
        if (!CollectionUtils.isEmpty(result.getMatchedEvents())) {
            Map<String, Object> attrs = result.getMatchedEvents().get(0).getAttributes();
            if (attrs != null) {
                for (Map.Entry<String, Object> entry : attrs.entrySet()) {
                    fields.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
        }
        return fields;
    }

    private void onWindowClosed(AlertEvent alert) {
        if (alert == null) {
            return;
        }

        boolean suppressed = suppressionService.checkSuppressed(alert);
        if (suppressed) {
            log.debug("收敛窗口告警被抑制: {}", alert.getAlertId());
            return;
        }

        suppressionService.recordSuppression(alert);
        fireAlertFinalized(alert);

        log.info("收敛窗口输出最终告警: alertId={}, ruleId={}, severity={}, hitCount={}",
                alert.getAlertId(),
                alert.getRuleId(),
                alert.getSeverity(),
                alert.getRuleHitCount());
    }

    public void registerRuleDefinition(RuleDefinition ruleDef) {
        if (ruleDef != null && ruleDef.getRuleId() != null) {
            ruleDefinitionMap.put(ruleDef.getRuleId(), ruleDef);
            escalationService.registerRuleDefinition(ruleDef);
            suppressionService.registerRuleDefinition(ruleDef);
        }
    }

    public void batchRegisterRuleDefinitions(Map<String, RuleDefinition> ruleMap) {
        if (!CollectionUtils.isEmpty(ruleMap)) {
            ruleDefinitionMap.putAll(ruleMap);
            escalationService.batchRegisterRuleDefinitions(ruleMap);
            suppressionService.batchRegisterRuleDefinitions(ruleMap);
            log.info("告警流水线注册规则定义: {} 条", ruleMap.size());
        }
    }

    public void unregisterRuleDefinition(String ruleId) {
        ruleDefinitionMap.remove(ruleId);
    }

    public void addListener(PipelineListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(PipelineListener listener) {
        listeners.remove(listener);
    }

    private void notifyBeforeDedup(String fingerprint, RuleEvaluationResult result) {
        for (PipelineListener listener : listeners) {
            try {
                listener.beforeDeduplication(fingerprint, result);
            } catch (Exception e) {
                log.warn("PipelineListener.beforeDeduplication异常", e);
            }
        }
    }

    private void notifyAfterDedup(String fingerprint, boolean duplicated, String existingAlertId) {
        for (PipelineListener listener : listeners) {
            try {
                listener.afterDeduplication(fingerprint, duplicated, existingAlertId);
            } catch (Exception e) {
                log.warn("PipelineListener.afterDedup异常", e);
            }
        }
    }

    private void notifyBeforeEscalation(AlertEvent alert) {
        for (PipelineListener listener : listeners) {
            try {
                listener.beforeEscalation(alert);
            } catch (Exception e) {
                log.warn("PipelineListener.beforeEscalation异常", e);
            }
        }
    }

    private void notifyAfterEscalation(AlertEvent alert, boolean escalated) {
        for (PipelineListener listener : listeners) {
            try {
                listener.afterEscalation(alert, escalated);
            } catch (Exception e) {
                log.warn("PipelineListener.afterEscalation异常", e);
            }
        }
    }

    private void notifyBeforeSuppression(AlertEvent alert) {
        for (PipelineListener listener : listeners) {
            try {
                listener.beforeSuppression(alert);
            } catch (Exception e) {
                log.warn("PipelineListener.beforeSuppression异常", e);
            }
        }
    }

    private void notifyAfterSuppression(AlertEvent alert, boolean suppressed) {
        for (PipelineListener listener : listeners) {
            try {
                listener.afterSuppression(alert, suppressed);
            } catch (Exception e) {
                log.warn("PipelineListener.afterSuppression异常", e);
            }
        }
    }

    private void notifyAlertProduced(AlertEvent alert) {
        for (PipelineListener listener : listeners) {
            try {
                listener.onAlertProduced(alert);
            } catch (Exception e) {
                log.warn("PipelineListener.onAlertProduced异常", e);
            }
        }
    }

    private void fireAlertFinalized(AlertEvent alert) {
        for (PipelineListener listener : listeners) {
            try {
                listener.onAlertFinalized(alert);
            } catch (Exception e) {
                log.warn("PipelineListener.onAlertFinalized异常", e);
            }
        }
    }

    private void firePipelineComplete(PipelineResult result) {
        for (PipelineListener listener : listeners) {
            try {
                listener.onPipelineComplete(result);
            } catch (Exception e) {
                log.warn("PipelineListener.onPipelineComplete异常", e);
            }
        }
    }

    public interface PipelineListener {
        default void beforeDeduplication(String fingerprint, RuleEvaluationResult result) {}
        default void afterDeduplication(String fingerprint, boolean duplicated, String existingAlertId) {}
        default void beforeEscalation(AlertEvent alert) {}
        default void afterEscalation(AlertEvent alert, boolean escalated) {}
        default void beforeSuppression(AlertEvent alert) {}
        default void afterSuppression(AlertEvent alert, boolean suppressed) {}
        default void onAlertProduced(AlertEvent alert) {}
        default void onAlertFinalized(AlertEvent alert) {}
        default void onPipelineComplete(PipelineResult result) {}
    }

    public static class PipelineResult {
        private final List<AlertEvent> outputAlerts = new ArrayList<>();
        private final List<String> deduplicatedFingerprints = new ArrayList<>();
        private final Map<String, Integer> deduplicatedHitCounts = new HashMap<>();
        private final Map<String, String> suppressedBy = new HashMap<>();
        private final Map<String, String> errors = new HashMap<>();

        private int deduplicatedCount = 0;
        private int suppressedCount = 0;
        private int errorCount = 0;

        public synchronized void addOutputAlert(AlertEvent alert) {
            outputAlerts.add(alert);
        }

        public synchronized void incrementDeduplicated() {
            deduplicatedCount++;
        }

        public synchronized void recordDeduplicated(String fingerprint, int hitCount) {
            deduplicatedFingerprints.add(fingerprint);
            deduplicatedHitCounts.put(fingerprint, hitCount);
        }

        public synchronized void incrementSuppressed() {
            suppressedCount++;
        }

        public synchronized void recordSuppressed(String fingerprint, String byRuleId) {
            suppressedBy.put(fingerprint, byRuleId);
        }

        public synchronized void recordError(String ruleId, Exception e) {
            errorCount++;
            errors.put(ruleId, e.getMessage() != null ? e.getMessage() : e.getClass().getName());
        }

        public List<AlertEvent> getOutputAlerts() {
            return outputAlerts;
        }

        public int getDeduplicatedCount() {
            return deduplicatedCount;
        }

        public int getSuppressedCount() {
            return suppressedCount;
        }

        public int getErrorCount() {
            return errorCount;
        }

        public List<String> getDeduplicatedFingerprints() {
            return deduplicatedFingerprints;
        }

        public Map<String, Integer> getDeduplicatedHitCounts() {
            return deduplicatedHitCounts;
        }

        public Map<String, String> getSuppressedBy() {
            return suppressedBy;
        }

        public Map<String, String> getErrors() {
            return errors;
        }
    }
}
