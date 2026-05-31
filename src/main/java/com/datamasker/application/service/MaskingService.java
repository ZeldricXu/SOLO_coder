package com.datamasker.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datamasker.domain.masking.model.MaskingResult;
import com.datamasker.domain.masking.model.MaskingRule;
import com.datamasker.domain.masking.model.MaskingStrategy;
import com.datamasker.domain.masking.strategy.MaskingStrategyFactory;
import com.datamasker.infrastructure.config.MaskingConfig;
import com.datamasker.infrastructure.persistence.entity.MaskingRuleEntity;
import com.datamasker.infrastructure.persistence.mapper.MaskingRuleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MaskingService {

    private final MaskingStrategyFactory maskingStrategyFactory;
    private final MaskingConfig maskingConfig;
    private final MaskingRuleMapper maskingRuleMapper;

    private static final List<String> LEVEL_HIERARCHY = List.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "SECRET");

    public List<MaskingResult> maskData(String userLevel, Map<String, String> fields, Map<String, String> fieldCategories) {
        List<MaskingRule> rules = getRules();
        List<MaskingResult> results = new ArrayList<>();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String fieldName = entry.getKey();
            String fieldValue = entry.getValue();
            String category = fieldCategories != null ? fieldCategories.getOrDefault(fieldName, "") : "";
            MaskingResult result = maskFieldInternal(userLevel, fieldName, fieldValue, category, rules);
            results.add(result);
        }
        return results;
    }

    public MaskingRule addRule(String fieldPattern, String strategy, String levelRequired, String params) {
        MaskingRuleEntity entity = new MaskingRuleEntity();
        entity.setFieldPattern(fieldPattern);
        entity.setStrategy(strategy);
        entity.setLevelRequired(levelRequired);
        entity.setParams(params);
        entity.setEnabled(true);
        entity.setCreatedAt(LocalDateTime.now());
        maskingRuleMapper.insert(entity);
        return toDomain(entity);
    }

    public MaskingRule updateRule(String ruleId, String strategy, String levelRequired, String params, boolean enabled) {
        MaskingRuleEntity entity = maskingRuleMapper.selectById(ruleId);
        if (entity == null) {
            throw new IllegalArgumentException("Rule not found: " + ruleId);
        }
        if (strategy != null) {
            entity.setStrategy(strategy);
        }
        if (levelRequired != null) {
            entity.setLevelRequired(levelRequired);
        }
        if (params != null) {
            entity.setParams(params);
        }
        entity.setEnabled(enabled);
        maskingRuleMapper.updateById(entity);
        return toDomain(entity);
    }

    public List<MaskingRule> getRules() {
        List<MaskingRuleEntity> entities = maskingRuleMapper.selectList(null);
        return entities.stream().map(this::toDomain).toList();
    }

    public void deleteRule(String ruleId) {
        maskingRuleMapper.deleteById(ruleId);
    }

    public MaskingResult maskField(String userLevel, String fieldName, String fieldValue, String category) {
        List<MaskingRule> rules = getRules();
        return maskFieldInternal(userLevel, fieldName, fieldValue, category, rules);
    }

    private MaskingResult maskFieldInternal(String userLevel, String fieldName, String fieldValue, String category, List<MaskingRule> rules) {
        MaskingRule matchedRule = findMatchingRule(fieldName, rules);
        MaskingResult result = new MaskingResult();
        result.setFieldName(fieldName);
        result.setOriginalValue(fieldValue);

        if (matchedRule == null || !matchedRule.isEnabled()) {
            result.setMaskedValue(fieldValue);
            result.setStrategy(matchedRule != null ? matchedRule.getStrategy().name() : "NONE");
            result.setWasMasked(false);
            return result;
        }

        if (!isLevelSufficient(userLevel, matchedRule.getLevelRequired())) {
            MaskingStrategyFactory.MaskingStrategyExecutor executor =
                    maskingStrategyFactory.getStrategy(matchedRule.getStrategy(), matchedRule.getParams());
            String maskedValue = executor.mask(fieldValue, matchedRule.getParams());
            result.setMaskedValue(maskedValue);
            result.setStrategy(matchedRule.getStrategy().name());
            result.setWasMasked(true);
        } else {
            result.setMaskedValue(fieldValue);
            result.setStrategy(matchedRule.getStrategy().name());
            result.setWasMasked(false);
        }

        return result;
    }

    private MaskingRule findMatchingRule(String fieldName, List<MaskingRule> rules) {
        for (MaskingRule rule : rules) {
            String pattern = rule.getFieldPattern();
            if (fieldName.equals(pattern) || fieldName.matches(pattern)) {
                return rule;
            }
        }
        return null;
    }

    private boolean isLevelSufficient(String userLevel, String levelRequired) {
        int userIndex = LEVEL_HIERARCHY.indexOf(userLevel.toUpperCase());
        int requiredIndex = LEVEL_HIERARCHY.indexOf(levelRequired.toUpperCase());
        if (userIndex == -1 || requiredIndex == -1) {
            return false;
        }
        return userIndex >= requiredIndex;
    }

    private MaskingRule toDomain(MaskingRuleEntity entity) {
        MaskingRule rule = new MaskingRule();
        rule.setRuleId(entity.getId());
        rule.setFieldPattern(entity.getFieldPattern());
        rule.setStrategy(MaskingStrategy.valueOf(entity.getStrategy()));
        rule.setLevelRequired(entity.getLevelRequired());
        rule.setParams(entity.getParams());
        rule.setEnabled(entity.getEnabled());
        return rule;
    }
}
