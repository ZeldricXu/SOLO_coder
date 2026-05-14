package com.example.mailservice.service;

import com.example.mailservice.config.AppConfig;
import com.example.mailservice.model.CategoryRule;
import com.example.mailservice.repository.CategoryRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuleConfigLoader {

    private final CategoryRuleRepository categoryRuleRepository;
    private final AppConfig appConfig;

    @PostConstruct
    @Transactional
    public void loadConfiguredRules() {
        if (appConfig.getRulePriority() == null || appConfig.getRulePriority().getRules() == null) {
            log.info("未配置分类规则，跳过加载");
            return;
        }

        for (AppConfig.RulePriorityConfig.RuleConfig ruleConfig : appConfig.getRulePriority().getRules()) {
            if (categoryRuleRepository.findByTargetCategory(ruleConfig.getTargetCategory()).isEmpty()) {
                createRuleFromConfig(ruleConfig);
            } else {
                log.debug("分类规则已存在，跳过创建，category: {}", ruleConfig.getTargetCategory());
            }
        }

        log.info("配置规则加载完成，配置规则数量: {}", appConfig.getRulePriority().getRules().size());
    }

    @Transactional
    public void reloadRules() {
        if (appConfig.getRulePriority() == null || appConfig.getRulePriority().getRules() == null) {
            return;
        }

        for (AppConfig.RulePriorityConfig.RuleConfig ruleConfig : appConfig.getRulePriority().getRules()) {
            if (ruleConfig.isEnabled()) {
                upsertRuleFromConfig(ruleConfig);
            }
        }
        log.info("规则重新加载完成");
    }

    private void createRuleFromConfig(AppConfig.RulePriorityConfig.RuleConfig ruleConfig) {
        CategoryRule rule = CategoryRule.builder()
                .ruleId("rule_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8))
                .ruleName(ruleConfig.getRuleName())
                .rulePattern(ruleConfig.getRulePattern())
                .targetCategory(ruleConfig.getTargetCategory())
                .rulePriority(ruleConfig.getBasePriority())
                .dynamicPriority(ruleConfig.getBasePriority())
                .matchCount(0)
                .enabled(ruleConfig.isEnabled())
                .build();
        categoryRuleRepository.save(rule);
        log.info("从配置创建分类规则: {} -> {}", rule.getRuleName(), rule.getTargetCategory());
    }

    private void upsertRuleFromConfig(AppConfig.RulePriorityConfig.RuleConfig ruleConfig) {
        categoryRuleRepository.findByTargetCategory(ruleConfig.getTargetCategory()).stream()
                .findFirst()
                .ifPresentOrElse(
                        existing -> {
                            existing.setRuleName(ruleConfig.getRuleName());
                            existing.setRulePattern(ruleConfig.getRulePattern());
                            existing.setEnabled(ruleConfig.isEnabled());
                            categoryRuleRepository.save(existing);
                            log.info("更新分类规则: {}", existing.getRuleName());
                        },
                        () -> createRuleFromConfig(ruleConfig)
                );
    }
}
