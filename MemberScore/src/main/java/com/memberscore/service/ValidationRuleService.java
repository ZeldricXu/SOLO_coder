package com.memberscore.service;

import com.memberscore.entity.PointRule;
import com.memberscore.entity.ValidationRule;
import com.memberscore.enums.ValidationType;
import com.memberscore.repository.ValidationRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ValidationRuleService {
    
    private final ValidationRuleRepository validationRuleRepository;
    
    @Transactional(readOnly = true)
    public Optional<ValidationRule> getValidationRuleForSource(String sourceType) {
        return validationRuleRepository.findBySourceTypeAndIsEnabledTrue(sourceType);
    }
    
    @Transactional(readOnly = true)
    public List<ValidationRule> getAllEnabledValidationRules() {
        return validationRuleRepository.findByIsEnabledTrue();
    }
    
    @Transactional(readOnly = true)
    public List<ValidationRule> getValidationRulesByType(ValidationType type) {
        return validationRuleRepository.findByValidationTypeAndIsEnabledTrue(type);
    }
    
    @Transactional(readOnly = true)
    public Optional<ValidationRule> getValidationRuleByRuleId(String ruleId) {
        return validationRuleRepository.findByRuleId(ruleId);
    }
    
    @Transactional
    public ValidationRule createValidationRule(ValidationRule rule) {
        if (rule.getRuleId() == null) {
            rule.setRuleId("val_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        }
        
        if (validationRuleRepository.existsBySourceType(rule.getSourceType())) {
            log.warn("该来源类型已存在校验规则: sourceType={}", rule.getSourceType());
        }
        
        ValidationRule saved = validationRuleRepository.save(rule);
        log.info("创建校验规则成功: ruleId={}, sourceType={}, validationType={}", 
                saved.getRuleId(), saved.getSourceType(), saved.getValidationType());
        return saved;
    }
    
    @Transactional
    public ValidationRule updateValidationRule(String ruleId, ValidationRule updatedRule) {
        ValidationRule existing = validationRuleRepository.findByRuleId(ruleId)
                .orElseThrow(() -> new RuntimeException("校验规则不存在: " + ruleId));
        
        existing.setRuleName(updatedRule.getRuleName());
        existing.setValidationType(updatedRule.getValidationType());
        existing.setMinAmount(updatedRule.getMinAmount());
        existing.setMaxAmount(updatedRule.getMaxAmount());
        existing.setAmountFactor(updatedRule.getAmountFactor());
        existing.setFixedPoints(updatedRule.getFixedPoints());
        existing.setTimeWindowMinutes(updatedRule.getTimeWindowMinutes());
        existing.setMaxPointsPerWindow(updatedRule.getMaxPointsPerWindow());
        existing.setValidationConfig(updatedRule.getValidationConfig());
        existing.setIsEnabled(updatedRule.getIsEnabled());
        existing.setDescription(updatedRule.getDescription());
        existing.setUpdatedAt(LocalDateTime.now());
        
        ValidationRule saved = validationRuleRepository.save(existing);
        log.info("更新校验规则成功: ruleId={}", saved.getRuleId());
        return saved;
    }
    
    @Transactional
    public void enableValidationRule(String ruleId) {
        ValidationRule rule = validationRuleRepository.findByRuleId(ruleId)
                .orElseThrow(() -> new RuntimeException("校验规则不存在: " + ruleId));
        rule.setIsEnabled(true);
        rule.setUpdatedAt(LocalDateTime.now());
        validationRuleRepository.save(rule);
        log.info("启用校验规则: ruleId={}", ruleId);
    }
    
    @Transactional
    public void disableValidationRule(String ruleId) {
        ValidationRule rule = validationRuleRepository.findByRuleId(ruleId)
                .orElseThrow(() -> new RuntimeException("校验规则不存在: " + ruleId));
        rule.setIsEnabled(false);
        rule.setUpdatedAt(LocalDateTime.now());
        validationRuleRepository.save(rule);
        log.info("禁用校验规则: ruleId={}", ruleId);
    }
    
    @Transactional(readOnly = true)
    public ValidationResult validateAndCalculatePoints(
            String sourceType, int baseAmount, PointRule pointRule) {
        
        Optional<ValidationRule> validationOpt = getValidationRuleForSource(sourceType);
        
        if (validationOpt.isEmpty()) {
            log.debug("未找到来源类型的校验规则，使用默认计算: sourceType={}", sourceType);
            return ValidationResult.builder()
                    .valid(true)
                    .calculatedPoints(calculateBasePoints(pointRule, baseAmount))
                    .message("使用默认计算规则")
                    .build();
        }
        
        ValidationRule validation = validationOpt.get();
        
        switch (validation.getValidationType()) {
            case AMOUNT_RELATED:
                return validateAmountRelated(validation, baseAmount, pointRule);
                
            case TIME_RELATED:
                return validateTimeRelated(validation, baseAmount, pointRule);
                
            case FIXED_AMOUNT:
                return validateFixedAmount(validation, pointRule);
                
            case CUSTOM:
                return validateCustom(validation, baseAmount, pointRule);
                
            default:
                return ValidationResult.builder()
                        .valid(true)
                        .calculatedPoints(calculateBasePoints(pointRule, baseAmount))
                        .message("未知校验类型，使用默认计算")
                        .build();
        }
    }
    
    private ValidationResult validateAmountRelated(
            ValidationRule validation, int baseAmount, PointRule pointRule) {
        
        if (validation.getMinAmount() != null && baseAmount < validation.getMinAmount()) {
            return ValidationResult.builder()
                    .valid(false)
                    .errorCode("AMOUNT_TOO_SMALL")
                    .message("金额低于最低要求: " + validation.getMinAmount())
                    .build();
        }
        
        if (validation.getMaxAmount() != null && baseAmount > validation.getMaxAmount()) {
            return ValidationResult.builder()
                    .valid(false)
                    .errorCode("AMOUNT_TOO_LARGE")
                    .message("金额超过最高限制: " + validation.getMaxAmount())
                    .build();
        }
        
        double factor = validation.getAmountFactor() != null ? validation.getAmountFactor() : 1.0;
        int points = (int) Math.round(baseAmount * factor * pointRule.getRuleMultiplier());
        
        return ValidationResult.builder()
                .valid(true)
                .calculatedPoints(points)
                .validationRuleId(validation.getRuleId())
                .message("金额关联校验通过")
                .build();
    }
    
    private ValidationResult validateTimeRelated(
            ValidationRule validation, int baseAmount, PointRule pointRule) {
        
        int points = calculateBasePoints(pointRule, baseAmount);
        
        if (validation.getMaxPointsPerWindow() != null && points > validation.getMaxPointsPerWindow()) {
            points = validation.getMaxPointsPerWindow();
        }
        
        return ValidationResult.builder()
                .valid(true)
                .calculatedPoints(points)
                .validationRuleId(validation.getRuleId())
                .timeWindowMinutes(validation.getTimeWindowMinutes())
                .message("时间关联校验通过")
                .build();
    }
    
    private ValidationResult validateFixedAmount(
            ValidationRule validation, PointRule pointRule) {
        
        int points = validation.getFixedPoints() != null 
                ? validation.getFixedPoints() 
                : pointRule.getRulePoints();
        
        return ValidationResult.builder()
                .valid(true)
                .calculatedPoints(points)
                .validationRuleId(validation.getRuleId())
                .message("固定金额校验通过")
                .build();
    }
    
    private ValidationResult validateCustom(
            ValidationRule validation, int baseAmount, PointRule pointRule) {
        
        int points = calculateBasePoints(pointRule, baseAmount);
        
        return ValidationResult.builder()
                .valid(true)
                .calculatedPoints(points)
                .validationRuleId(validation.getRuleId())
                .message("自定义校验通过")
                .build();
    }
    
    private int calculateBasePoints(PointRule pointRule, int baseAmount) {
        return (int) Math.round(baseAmount * pointRule.getRulePoints() * pointRule.getRuleMultiplier());
    }
    
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ValidationResult {
        private boolean valid;
        private int calculatedPoints;
        private String validationRuleId;
        private String errorCode;
        private String message;
        private Integer timeWindowMinutes;
    }
}
