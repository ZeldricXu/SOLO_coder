package com.memberscore.service;

import com.memberscore.entity.PointRule;
import com.memberscore.repository.PointRuleRepository;
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
public class PointRuleService {
    
    private final PointRuleRepository pointRuleRepository;
    
    @Transactional(readOnly = true)
    public Optional<PointRule> getActiveRule(String ruleType) {
        return pointRuleRepository.findActiveRuleByType(ruleType, LocalDateTime.now());
    }
    
    @Transactional(readOnly = true)
    public List<PointRule> getAllEnabledRules() {
        return pointRuleRepository.findByRuleEnabledTrue();
    }
    
    @Transactional(readOnly = true)
    public Optional<PointRule> getRuleByRuleId(String ruleId) {
        return pointRuleRepository.findByRuleId(ruleId);
    }
    
    @Transactional
    public PointRule createRule(PointRule rule) {
        if (rule.getRuleId() == null) {
            rule.setRuleId("rule_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        }
        PointRule saved = pointRuleRepository.save(rule);
        log.info("创建积分规则成功: ruleId={}, ruleType={}", saved.getRuleId(), saved.getRuleType());
        return saved;
    }
    
    @Transactional
    public PointRule updateRule(String ruleId, PointRule updatedRule) {
        PointRule existing = pointRuleRepository.findByRuleId(ruleId)
                .orElseThrow(() -> new RuntimeException("规则不存在: " + ruleId));
        
        existing.setRuleName(updatedRule.getRuleName());
        existing.setRulePoints(updatedRule.getRulePoints());
        existing.setRuleMultiplier(updatedRule.getRuleMultiplier());
        existing.setRuleEnabled(updatedRule.getRuleEnabled());
        existing.setRuleDescription(updatedRule.getRuleDescription());
        existing.setStartDate(updatedRule.getStartDate());
        existing.setEndDate(updatedRule.getEndDate());
        existing.setUpdatedAt(LocalDateTime.now());
        
        PointRule saved = pointRuleRepository.save(existing);
        log.info("更新积分规则成功: ruleId={}", saved.getRuleId());
        return saved;
    }
    
    @Transactional
    public void enableRule(String ruleId) {
        PointRule rule = pointRuleRepository.findByRuleId(ruleId)
                .orElseThrow(() -> new RuntimeException("规则不存在: " + ruleId));
        rule.setRuleEnabled(true);
        rule.setUpdatedAt(LocalDateTime.now());
        pointRuleRepository.save(rule);
        log.info("启用积分规则: ruleId={}", ruleId);
    }
    
    @Transactional
    public void disableRule(String ruleId) {
        PointRule rule = pointRuleRepository.findByRuleId(ruleId)
                .orElseThrow(() -> new RuntimeException("规则不存在: " + ruleId));
        rule.setRuleEnabled(false);
        rule.setUpdatedAt(LocalDateTime.now());
        pointRuleRepository.save(rule);
        log.info("禁用积分规则: ruleId={}", ruleId);
    }
    
    public int calculatePoints(PointRule rule, int baseAmount, double levelMultiplier) {
        if (rule == null) {
            return 0;
        }
        double points = rule.getRulePoints() * rule.getRuleMultiplier() * levelMultiplier;
        return (int) Math.round(points);
    }
}
