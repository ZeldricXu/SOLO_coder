package com.iotconnect.service;

import com.iotconnect.entity.AlertRule;
import com.iotconnect.enums.AlertOperator;
import com.iotconnect.enums.AlertSeverity;
import com.iotconnect.repository.AlertRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AlertConfigService {

    private static final Logger logger = LoggerFactory.getLogger(AlertConfigService.class);

    private final AlertRuleRepository alertRuleRepository;

    public AlertConfigService(AlertRuleRepository alertRuleRepository) {
        this.alertRuleRepository = alertRuleRepository;
    }

    @Transactional
    public AlertRule createAlertRule(AlertRule rule) {
        validateRule(rule);

        rule.setRuleId(generateRuleId());
        rule.setCreatedAt(LocalDateTime.now());
        rule.setUpdatedAt(LocalDateTime.now());
        
        if (rule.getEnabled() == null) {
            rule.setEnabled(true);
        }
        
        if (rule.getSilenceDurationSeconds() == null) {
            rule.setSilenceDurationSeconds(300);
        }

        AlertRule savedRule = alertRuleRepository.save(rule);
        logger.info("Alert rule created: ruleId={}, ruleName={}", savedRule.getRuleId(), savedRule.getRuleName());
        
        return savedRule;
    }

    @Transactional
    public AlertRule updateAlertRule(String ruleId, AlertRule updatedRule) {
        Optional<AlertRule> existingRuleOpt = alertRuleRepository.findById(ruleId);
        
        if (existingRuleOpt.isEmpty()) {
            throw new RuntimeException("Alert rule not found: " + ruleId);
        }

        AlertRule existingRule = existingRuleOpt.get();
        
        if (updatedRule.getRuleName() != null) {
            existingRule.setRuleName(updatedRule.getRuleName());
        }
        if (updatedRule.getDeviceType() != null) {
            existingRule.setDeviceType(updatedRule.getDeviceType());
        }
        if (updatedRule.getMetric() != null) {
            existingRule.setMetric(updatedRule.getMetric());
        }
        if (updatedRule.getThreshold() != null) {
            existingRule.setThreshold(updatedRule.getThreshold());
        }
        if (updatedRule.getOperator() != null) {
            existingRule.setOperator(updatedRule.getOperator());
        }
        if (updatedRule.getSeverity() != null) {
            existingRule.setSeverity(updatedRule.getSeverity());
        }
        if (updatedRule.getNotifyChannels() != null) {
            existingRule.setNotifyChannels(updatedRule.getNotifyChannels());
        }
        if (updatedRule.getEnabled() != null) {
            existingRule.setEnabled(updatedRule.getEnabled());
        }
        if (updatedRule.getDescription() != null) {
            existingRule.setDescription(updatedRule.getDescription());
        }
        if (updatedRule.getSilenceDurationSeconds() != null) {
            existingRule.setSilenceDurationSeconds(updatedRule.getSilenceDurationSeconds());
        }
        
        existingRule.setUpdatedAt(LocalDateTime.now());

        validateRule(existingRule);
        
        AlertRule savedRule = alertRuleRepository.save(existingRule);
        logger.info("Alert rule updated: ruleId={}", ruleId);
        
        return savedRule;
    }

    @Transactional
    public void deleteAlertRule(String ruleId) {
        if (!alertRuleRepository.existsById(ruleId)) {
            throw new RuntimeException("Alert rule not found: " + ruleId);
        }
        
        alertRuleRepository.deleteById(ruleId);
        logger.info("Alert rule deleted: ruleId={}", ruleId);
    }

    public Optional<AlertRule> getAlertRule(String ruleId) {
        return alertRuleRepository.findById(ruleId);
    }

    public List<AlertRule> getAllAlertRules() {
        return alertRuleRepository.findAll();
    }

    public List<AlertRule> getEnabledAlertRules() {
        return alertRuleRepository.findByEnabledTrue();
    }

    public List<AlertRule> getRulesByDeviceType(String deviceType) {
        return alertRuleRepository.findByDeviceTypeAndEnabledTrue(deviceType);
    }

    public List<AlertRule> getRulesByDeviceTypeAndMetric(String deviceType, String metric) {
        return alertRuleRepository.findByDeviceTypeAndMetricAndEnabledTrue(deviceType, metric);
    }

    @Transactional
    public AlertRule enableRule(String ruleId) {
        return updateRuleStatus(ruleId, true);
    }

    @Transactional
    public AlertRule disableRule(String ruleId) {
        return updateRuleStatus(ruleId, false);
    }

    private AlertRule updateRuleStatus(String ruleId, boolean enabled) {
        Optional<AlertRule> ruleOpt = alertRuleRepository.findById(ruleId);
        
        if (ruleOpt.isEmpty()) {
            throw new RuntimeException("Alert rule not found: " + ruleId);
        }

        AlertRule rule = ruleOpt.get();
        rule.setEnabled(enabled);
        rule.setUpdatedAt(LocalDateTime.now());

        AlertRule savedRule = alertRuleRepository.save(rule);
        logger.info("Alert rule {}: ruleId={}", enabled ? "enabled" : "disabled", ruleId);
        
        return savedRule;
    }

    private void validateRule(AlertRule rule) {
        if (rule.getRuleName() == null || rule.getRuleName().trim().isEmpty()) {
            throw new IllegalArgumentException("Rule name cannot be null or empty");
        }
        
        if (rule.getDeviceType() == null || rule.getDeviceType().trim().isEmpty()) {
            throw new IllegalArgumentException("Device type cannot be null or empty");
        }
        
        if (rule.getMetric() == null || rule.getMetric().trim().isEmpty()) {
            throw new IllegalArgumentException("Metric cannot be null or empty");
        }
        
        if (rule.getThreshold() == null) {
            throw new IllegalArgumentException("Threshold cannot be null");
        }
        
        if (rule.getOperator() == null) {
            throw new IllegalArgumentException("Operator cannot be null");
        }
        
        try {
            AlertOperator.fromValue(rule.getOperator());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid operator: " + rule.getOperator());
        }
        
        if (rule.getSeverity() == null) {
            throw new IllegalArgumentException("Severity cannot be null");
        }
        
        try {
            AlertSeverity.fromValue(rule.getSeverity());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid severity: " + rule.getSeverity());
        }
    }

    private String generateRuleId() {
        return "rule_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
