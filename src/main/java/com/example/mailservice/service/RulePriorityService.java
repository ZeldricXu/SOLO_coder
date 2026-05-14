package com.example.mailservice.service;

import com.example.mailservice.config.AppConfig;
import com.example.mailservice.model.CategoryRule;
import com.example.mailservice.repository.CategoryRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RulePriorityService {

    private final CategoryRuleRepository categoryRuleRepository;
    private final AppConfig appConfig;

    @Transactional
    public void updateRulePriority(String ruleId, int newPriority) {
        int minPriority = appConfig.getRulePriority().getMinPriority();
        int maxPriority = appConfig.getRulePriority().getMaxPriority();
        int clampedPriority = Math.max(minPriority, Math.min(maxPriority, newPriority));

        categoryRuleRepository.updateDynamicPriority(ruleId, clampedPriority);
        log.info("更新规则动态优先级，ruleId: {}, 新优先级: {}", ruleId, clampedPriority);
    }

    @Transactional
    public void recordMatchAndAdjust(String ruleId) {
        categoryRuleRepository.incrementMatchCount(ruleId);

        categoryRuleRepository.findByRuleId(ruleId).ifPresent(rule -> {
            int matchCount = rule.getMatchCount() != null ? rule.getMatchCount() : 0;
            int adjustmentThreshold = appConfig.getRulePriority().getAdjustmentThreshold();

            if (matchCount > 0 && matchCount % adjustmentThreshold == 0) {
                int currentPriority = rule.getDynamicPriority() != null ? rule.getDynamicPriority() : rule.getRulePriority();
                int basePriority = rule.getRulePriority() != null ? rule.getRulePriority() : 50;

                double increment = Math.log10(matchCount) * 10;
                int newPriority = basePriority + (int) increment;

                updateRulePriority(ruleId, newPriority);
            }
        });
    }

    @Transactional
    public List<CategoryRule> getActiveRulesSorted() {
        if (appConfig.getRulePriority() != null && appConfig.getRulePriority().isDynamicSortingEnabled()) {
            return categoryRuleRepository.findByEnabledTrueOrderByDynamicPriorityDesc();
        }
        return categoryRuleRepository.findByEnabledTrueOrderByRulePriorityDesc();
    }

    @Scheduled(fixedRateString = "${app.rule-priority.decay-interval-minutes:60}000")
    @Transactional
    public void applyDecay() {
        if (appConfig.getRulePriority() == null || !appConfig.getRulePriority().isDynamicSortingEnabled()) {
            return;
        }

        double decayFactor = appConfig.getRulePriority().getDecayFactor();
        List<CategoryRule> allRules = categoryRuleRepository.findAll();

        for (CategoryRule rule : allRules) {
            if (rule.getMatchCount() != null && rule.getMatchCount() > 0) {
                int newMatchCount = (int) (rule.getMatchCount() * decayFactor);
                rule.setMatchCount(newMatchCount);
                categoryRuleRepository.save(rule);
                log.debug("应用优先级衰减，rule: {}, 原匹配数: {}, 新匹配数: {}",
                        rule.getRuleName(), rule.getMatchCount(), newMatchCount);
            }
        }

        log.info("优先级衰减计算完成，规则数量: {}", allRules.size());
    }
}
