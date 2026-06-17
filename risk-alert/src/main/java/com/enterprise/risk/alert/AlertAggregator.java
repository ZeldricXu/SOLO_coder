package com.enterprise.risk.alert;

import com.enterprise.risk.common.alert.AlertEvent;
import com.enterprise.risk.common.alert.AlertSeverity;
import com.enterprise.risk.common.event.RiskEvent;
import com.enterprise.risk.common.rule.RuleEvaluationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class AlertAggregator {

    private final Map<String, AlertEvent> aggregateStore = new ConcurrentHashMap<>();

    public AlertEvent aggregate(String fingerprint,
                                RuleEvaluationResult result,
                                AlertSeverity severity,
                                String description) {
        AlertEvent existing = aggregateStore.get(fingerprint);

        if (existing == null) {
            return createNewAlert(fingerprint, result, severity, description);
        }

        return updateExistingAlert(existing, result);
    }

    private AlertEvent createNewAlert(String fingerprint,
                                      RuleEvaluationResult result,
                                      AlertSeverity severity,
                                      String description) {
        AlertEvent alert = AlertEvent.builder()
                .alertId(UUID.randomUUID().toString())
                .fingerprint(fingerprint)
                .ruleId(result.getRuleId())
                .ruleName(result.getRuleName())
                .severity(severity)
                .description(description)
                .riskScore(result.getFinalScore() != null ? result.getFinalScore() :
                        (result.getRuleScore() != null ? result.getRuleScore() : 0.0))
                .eventCount(1)
                .ruleHitCount(1)
                .triggeredEventIds(new ArrayList<>())
                .build();

        extractEventInfo(result, alert);
        aggregateStore.put(fingerprint, alert);

        if (log.isDebugEnabled()) {
            log.debug("创建新告警: alertId={}, fingerprint={}",
                    alert.getAlertId(), fingerprint);
        }

        return alert;
    }

    private AlertEvent updateExistingAlert(AlertEvent existing,
                                           RuleEvaluationResult result) {
        synchronized (existing) {
            existing.incrementRuleHitCount();
            existing.incrementEventCount();

            List<RiskEvent> matchedEvents = result.getMatchedEvents();
            if (!CollectionUtils.isEmpty(matchedEvents)) {
                RiskEvent firstEvent = matchedEvents.get(0);
                long timestamp = firstEvent.getTimestamp() != null
                        ? firstEvent.getTimestamp()
                        : System.currentTimeMillis();
                existing.updateEventTime(timestamp);

                for (RiskEvent event : matchedEvents) {
                    existing.addTriggeredEvent(event.getEventId());
                }
            } else {
                existing.updateEventTime(System.currentTimeMillis());
            }

            if (result.getFinalScore() != null
                    && result.getFinalScore() > existing.getRiskScore()) {
                existing.setRiskScore(result.getFinalScore());
            }

            if (result.getMatchedReasons() != null && !result.getMatchedReasons().isEmpty()) {
                if (existing.getMetadata() == null) {
                    existing.setMetadata(new ConcurrentHashMap<>());
                }
                @SuppressWarnings("unchecked")
                List<String> reasons = (List<String>) existing.getMetadata()
                        .computeIfAbsent("reasons", k -> new ArrayList<>());
                for (String reason : result.getMatchedReasons()) {
                    if (!reasons.contains(reason) && reasons.size() < 50) {
                        reasons.add(reason);
                    }
                }
            }

            if (log.isDebugEnabled()) {
                log.debug("更新告警: alertId={}, ruleHitCount={}, eventCount={}",
                        existing.getAlertId(),
                        existing.getRuleHitCount(),
                        existing.getEventCount());
            }
        }

        return existing;
    }

    private void extractEventInfo(RuleEvaluationResult result, AlertEvent alert) {
        List<RiskEvent> matchedEvents = result.getMatchedEvents();

        if (!CollectionUtils.isEmpty(matchedEvents)) {
            RiskEvent firstEvent = matchedEvents.get(0);

            alert.setEntityId(firstEvent.getEntityId());
            alert.setEntityType(firstEvent.getEntityType());
            alert.setBusinessLine(firstEvent.getBusinessLine());

            long timestamp = firstEvent.getTimestamp() != null
                    ? firstEvent.getTimestamp()
                    : System.currentTimeMillis();
            alert.setFirstEventTime(timestamp);
            alert.setLastEventTime(timestamp);

            for (RiskEvent event : matchedEvents) {
                alert.addTriggeredEvent(event.getEventId());
                if (event.getTimestamp() != null) {
                    alert.updateEventTime(event.getTimestamp());
                }
            }
        }

        if (alert.getFirstEventTime() == null) {
            long now = System.currentTimeMillis();
            alert.setFirstEventTime(now);
            alert.setLastEventTime(now);
        }
    }

    public AlertEvent getAggregatedAlert(String fingerprint) {
        AlertEvent alert = aggregateStore.get(fingerprint);
        if (alert != null) {
            synchronized (alert) {
                AlertEvent copy = cloneAlert(alert);
                return copy;
            }
        }
        return null;
    }

    public AlertEvent removeAndGet(String fingerprint) {
        AlertEvent alert = aggregateStore.remove(fingerprint);
        if (alert != null) {
            synchronized (alert) {
                AlertEvent copy = cloneAlert(alert);
                log.debug("移除聚合告警: alertId={}, fingerprint={}",
                        copy.getAlertId(), fingerprint);
                return copy;
            }
        }
        return null;
    }

    public boolean contains(String fingerprint) {
        return aggregateStore.containsKey(fingerprint);
    }

    public int getAggregatedCount() {
        return aggregateStore.size();
    }

    public void clearAll() {
        int count = aggregateStore.size();
        aggregateStore.clear();
        log.info("已清空聚合器，共清除 {} 条告警", count);
    }

    private AlertEvent cloneAlert(AlertEvent source) {
        AlertEvent target = AlertEvent.builder()
                .alertId(source.getAlertId())
                .fingerprint(source.getFingerprint())
                .ruleId(source.getRuleId())
                .ruleName(source.getRuleName())
                .severity(source.getSeverity())
                .entityId(source.getEntityId())
                .entityType(source.getEntityType())
                .businessLine(source.getBusinessLine())
                .description(source.getDescription())
                .riskScore(source.getRiskScore())
                .ruleHitCount(source.getRuleHitCount())
                .eventCount(source.getEventCount())
                .firstEventTime(source.getFirstEventTime())
                .lastEventTime(source.getLastEventTime())
                .createdAt(source.getCreatedAt())
                .status(source.getStatus())
                .suppressedBy(source.getSuppressedBy())
                .triggeredEventIds(source.getTriggeredEventIds() != null
                        ? new ArrayList<>(source.getTriggeredEventIds())
                        : new ArrayList<>())
                .metadata(source.getMetadata() != null
                        ? new ConcurrentHashMap<>(source.getMetadata())
                        : new ConcurrentHashMap<>())
                .actions(source.getActions() != null
                        ? new ArrayList<>(source.getActions())
                        : new ArrayList<>())
                .build();
        return target;
    }
}
