package com.healthtrack.service;

import com.healthtrack.entity.AdviceRule;
import com.healthtrack.entity.HealthIndicator;
import com.healthtrack.repository.AdviceRuleRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class AdviceRuleService {

    private static final Logger logger = LoggerFactory.getLogger(AdviceRuleService.class);

    @Autowired
    private AdviceRuleRepository adviceRuleRepository;

    @Value("${healthtrack.advice.rule.enabled:true}")
    private boolean rulesEnabled;

    @Value("${healthtrack.advice.rule.use-custom-rules:false}")
    private boolean useCustomRules;

    private final Map<Long, AdviceRule> ruleCache = new ConcurrentHashMap<>();
    private volatile List<AdviceRule> globalRulesCache = new ArrayList<>();

    @PostConstruct
    public void init() {
        logger.info("初始化建议规则服务，规则启用: {}, 使用自定义规则: {}", rulesEnabled, useCustomRules);
        if (rulesEnabled) {
            initializeDefaultRules();
            loadRulesToCache();
        }
    }

    private void initializeDefaultRules() {
        try {
            long existingCount = adviceRuleRepository.count();
            if (existingCount > 0) {
                logger.info("已有建议规则，跳过初始化: count={}", existingCount);
                return;
            }

            logger.info("创建默认建议规则");
            List<AdviceRule> defaultRules = createDefaultRules();
            adviceRuleRepository.saveAll(defaultRules);
            logger.info("默认建议规则创建完成: count={}", defaultRules.size());
        } catch (Exception e) {
            logger.warn("初始化默认建议规则失败: {}", e.getMessage());
        }
    }

    private List<AdviceRule> createDefaultRules() {
        List<AdviceRule> rules = new ArrayList<>();

        AdviceRule highHrRule = new AdviceRule();
        highHrRule.setRuleName("心率偏高规则");
        highHrRule.setRuleType("abnormal");
        highHrRule.setPriority("high");
        highHrRule.setIndicatorType("heart_rate");
        highHrRule.setConditionType("GREATER_THAN");
        highHrRule.setConditionValue(100.0);
        highHrRule.setAdviceContent("您的心率偏高（{value} bpm），建议放松心情、减少剧烈运动，如持续偏高请咨询医生。");
        highHrRule.setAdviceType("cardiovascular");
        highHrRule.setRuleOrder(1);
        highHrRule.setIsGlobal(true);
        rules.add(highHrRule);

        AdviceRule lowHrRule = new AdviceRule();
        lowHrRule.setRuleName("心率偏低规则");
        lowHrRule.setRuleType("abnormal");
        lowHrRule.setPriority("high");
        lowHrRule.setIndicatorType("heart_rate");
        lowHrRule.setConditionType("LESS_THAN");
        lowHrRule.setConditionValue(60.0);
        lowHrRule.setAdviceContent("您的心率偏低（{value} bpm），建议适当活动身体，如有不适请及时就医。");
        lowHrRule.setAdviceType("cardiovascular");
        lowHrRule.setRuleOrder(2);
        lowHrRule.setIsGlobal(true);
        rules.add(lowHrRule);

        AdviceRule highWeightRule = new AdviceRule();
        highWeightRule.setRuleName("体重偏高规则");
        highWeightRule.setRuleType("abnormal");
        highWeightRule.setPriority("high");
        highWeightRule.setIndicatorType("weight");
        highWeightRule.setConditionType("GREATER_THAN");
        highWeightRule.setConditionValue(120.0);
        highWeightRule.setAdviceContent("您的体重偏高（{value} kg），建议控制饮食，增加运动，保持健康的生活方式。");
        highWeightRule.setAdviceType("weight");
        highWeightRule.setRuleOrder(3);
        highWeightRule.setIsGlobal(true);
        rules.add(highWeightRule);

        AdviceRule lowWeightRule = new AdviceRule();
        lowWeightRule.setRuleName("体重偏低规则");
        lowWeightRule.setRuleType("abnormal");
        lowWeightRule.setPriority("medium");
        lowWeightRule.setIndicatorType("weight");
        lowWeightRule.setConditionType("LESS_THAN");
        lowWeightRule.setConditionValue(40.0);
        lowWeightRule.setAdviceContent("您的体重偏低（{value} kg），建议增加营养摄入，保持均衡饮食。");
        lowWeightRule.setAdviceType("weight");
        lowWeightRule.setRuleOrder(4);
        lowWeightRule.setIsGlobal(true);
        rules.add(lowWeightRule);

        AdviceRule highBpSysRule = new AdviceRule();
        highBpSysRule.setRuleName("收缩压偏高规则");
        highBpSysRule.setRuleType("abnormal");
        highBpSysRule.setPriority("high");
        highBpSysRule.setIndicatorType("blood_pressure_systolic");
        highBpSysRule.setConditionType("GREATER_THAN");
        highBpSysRule.setConditionValue(140.0);
        highBpSysRule.setAdviceContent("您的收缩压偏高（{value} mmHg），建议低盐饮食，避免情绪激动，定期监测。");
        highBpSysRule.setAdviceType("cardiovascular");
        highBpSysRule.setRuleOrder(5);
        highBpSysRule.setIsGlobal(true);
        rules.add(highBpSysRule);

        AdviceRule lowBpSysRule = new AdviceRule();
        lowBpSysRule.setRuleName("收缩压偏低规则");
        lowBpSysRule.setRuleType("abnormal");
        lowBpSysRule.setPriority("medium");
        lowBpSysRule.setIndicatorType("blood_pressure_systolic");
        lowBpSysRule.setConditionType("LESS_THAN");
        lowBpSysRule.setConditionValue(90.0);
        lowBpSysRule.setAdviceContent("您的收缩压偏低（{value} mmHg），建议多喝水，避免突然站立。");
        lowBpSysRule.setAdviceType("cardiovascular");
        lowBpSysRule.setRuleOrder(6);
        lowBpSysRule.setIsGlobal(true);
        rules.add(lowBpSysRule);

        AdviceRule highTempRule = new AdviceRule();
        highTempRule.setRuleName("体温偏高规则");
        highTempRule.setRuleType("abnormal");
        highTempRule.setPriority("high");
        highTempRule.setIndicatorType("temperature");
        highTempRule.setConditionType("GREATER_THAN");
        highTempRule.setConditionValue(37.5);
        highTempRule.setAdviceContent("您的体温偏高（{value} °C），可能有发热情况，建议多喝水、休息，如持续发热请就医。");
        highTempRule.setAdviceType("health");
        highTempRule.setRuleOrder(7);
        highTempRule.setIsGlobal(true);
        rules.add(highTempRule);

        AdviceRule lowTempRule = new AdviceRule();
        lowTempRule.setRuleName("体温偏低规则");
        lowTempRule.setRuleType("abnormal");
        lowTempRule.setPriority("medium");
        lowTempRule.setIndicatorType("temperature");
        lowTempRule.setConditionType("LESS_THAN");
        lowTempRule.setConditionValue(36.5);
        lowTempRule.setAdviceContent("您的体温偏低（{value} °C），建议注意保暖，适当喝温水。");
        lowTempRule.setAdviceType("health");
        lowTempRule.setRuleOrder(8);
        lowTempRule.setIsGlobal(true);
        rules.add(lowTempRule);

        AdviceRule lowStepsRule = new AdviceRule();
        lowStepsRule.setRuleName("步数不足规则");
        lowStepsRule.setRuleType("abnormal");
        lowStepsRule.setPriority("medium");
        lowStepsRule.setIndicatorType("steps");
        lowStepsRule.setConditionType("LESS_THAN");
        lowStepsRule.setConditionValue(3000.0);
        lowStepsRule.setAdviceContent("您今日步数较少（{value}步），建议适当增加运动，每天至少步行30分钟。");
        lowStepsRule.setAdviceType("exercise");
        lowStepsRule.setRuleOrder(9);
        lowStepsRule.setIsGlobal(true);
        rules.add(lowStepsRule);

        AdviceRule lowSleepRule = new AdviceRule();
        lowSleepRule.setRuleName("睡眠不足规则");
        lowSleepRule.setRuleType("abnormal");
        lowSleepRule.setPriority("medium");
        lowSleepRule.setIndicatorType("sleep_hours");
        lowSleepRule.setConditionType("LESS_THAN");
        lowSleepRule.setConditionValue(6.0);
        lowSleepRule.setAdviceContent("您的睡眠时间不足（{value} 小时），建议保持规律作息，保证充足睡眠。");
        lowSleepRule.setAdviceType("sleep");
        lowSleepRule.setRuleOrder(10);
        lowSleepRule.setIsGlobal(true);
        rules.add(lowSleepRule);

        AdviceRule highSleepRule = new AdviceRule();
        highSleepRule.setRuleName("睡眠过长规则");
        highSleepRule.setRuleType("abnormal");
        highSleepRule.setPriority("low");
        highSleepRule.setIndicatorType("sleep_hours");
        highSleepRule.setConditionType("GREATER_THAN");
        highSleepRule.setConditionValue(10.0);
        highSleepRule.setAdviceContent("您的睡眠时间过长（{value} 小时），建议保持规律作息，适当增加活动。");
        highSleepRule.setAdviceType("sleep");
        highSleepRule.setRuleOrder(11);
        highSleepRule.setIsGlobal(true);
        rules.add(highSleepRule);

        AdviceRule normalHrRule = new AdviceRule();
        normalHrRule.setRuleName("心率正常规则");
        normalHrRule.setRuleType("maintenance");
        normalHrRule.setPriority("low");
        normalHrRule.setIndicatorType("heart_rate");
        normalHrRule.setConditionType("STATUS_NORMAL");
        normalHrRule.setAdviceContent("您的心率保持良好（{value} bpm），继续保持健康的生活习惯！");
        normalHrRule.setAdviceType("maintenance");
        normalHrRule.setRuleOrder(20);
        normalHrRule.setIsGlobal(true);
        rules.add(normalHrRule);

        return rules;
    }

    private void loadRulesToCache() {
        try {
            List<AdviceRule> globalRules = adviceRuleRepository.findByEnabledTrueAndIsGlobalTrueOrderByRuleOrderAsc();
            globalRulesCache = globalRules;
            for (AdviceRule rule : globalRules) {
                ruleCache.put(rule.getId(), rule);
            }
            logger.info("已加载 {} 个全局建议规则到缓存", globalRules.size());
        } catch (Exception e) {
            logger.error("加载建议规则到缓存失败: {}", e.getMessage(), e);
        }
    }

    public List<AdviceRule> getApplicableRules(String userId) {
        if (!rulesEnabled) {
            return Collections.emptyList();
        }

        try {
            if (useCustomRules) {
                return adviceRuleRepository.findApplicableRules(userId);
            }
            return new ArrayList<>(globalRulesCache);
        } catch (Exception e) {
            logger.error("获取适用规则失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    public List<AdviceRule> getApplicableRulesByIndicator(String userId, String indicatorType) {
        if (!rulesEnabled) {
            return Collections.emptyList();
        }

        try {
            if (useCustomRules) {
                return adviceRuleRepository.findApplicableRulesByIndicatorType(userId, indicatorType);
            }
            return globalRulesCache.stream()
                    .filter(r -> indicatorType.equalsIgnoreCase(r.getIndicatorType()))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("获取指标适用规则失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    public AdviceRuleMatchResult matchRule(String userId, HealthIndicator indicator) {
        if (!rulesEnabled || indicator == null) {
            return null;
        }

        List<AdviceRule> rules = getApplicableRulesByIndicator(userId, indicator.getIndicatorType());
        
        for (AdviceRule rule : rules) {
            if (matchesCondition(rule, indicator)) {
                String content = rule.getAdviceContent()
                        .replace("{value}", String.format("%.1f", indicator.getCurrentValue()));
                return new AdviceRuleMatchResult(rule, content);
            }
        }
        
        return null;
    }

    private boolean matchesCondition(AdviceRule rule, HealthIndicator indicator) {
        if (rule == null || indicator == null) {
            return false;
        }

        String conditionType = rule.getConditionType();
        Double value = indicator.getCurrentValue();

        switch (conditionType) {
            case "LESS_THAN":
                return value < rule.getConditionValue();
            case "GREATER_THAN":
                return value > rule.getConditionValue();
            case "EQUAL":
                return value.equals(rule.getConditionValue());
            case "BELOW_RANGE":
                return value < rule.getConditionMin();
            case "ABOVE_RANGE":
                return value > rule.getConditionMax();
            case "OUTSIDE_RANGE":
                return value < rule.getConditionMin() || value > rule.getConditionMax();
            case "STATUS_ABNORMAL":
                return "abnormal".equalsIgnoreCase(indicator.getStatus());
            case "STATUS_NORMAL":
                return "normal".equalsIgnoreCase(indicator.getStatus());
            default:
                return false;
        }
    }

    public AdviceRule createRule(AdviceRule rule) {
        rule.setCreatedAt(LocalDateTime.now());
        rule.setUpdatedAt(LocalDateTime.now());
        if (rule.getEnabled() == null) {
            rule.setEnabled(true);
        }
        if (rule.getIsGlobal() == null) {
            rule.setIsGlobal(true);
        }
        if (rule.getRuleOrder() == null) {
            rule.setRuleOrder(100);
        }
        AdviceRule saved = adviceRuleRepository.save(rule);
        refreshCache();
        logger.info("创建建议规则: id={}, name={}", saved.getId(), saved.getRuleName());
        return saved;
    }

    public Optional<AdviceRule> getRuleById(Long id) {
        return adviceRuleRepository.findById(id);
    }

    public List<AdviceRule> getAllRules() {
        return adviceRuleRepository.findAll();
    }

    public List<AdviceRule> getGlobalRules() {
        return adviceRuleRepository.findByIsGlobalTrue();
    }

    public List<AdviceRule> getUserRules(String userId) {
        return adviceRuleRepository.findByUserId(userId);
    }

    public AdviceRule updateRule(Long id, AdviceRule updatedRule) {
        return adviceRuleRepository.findById(id)
                .map(rule -> {
                    rule.setRuleName(updatedRule.getRuleName());
                    rule.setRuleType(updatedRule.getRuleType());
                    rule.setPriority(updatedRule.getPriority());
                    rule.setIndicatorType(updatedRule.getIndicatorType());
                    rule.setConditionType(updatedRule.getConditionType());
                    rule.setConditionOperator(updatedRule.getConditionOperator());
                    rule.setConditionValue(updatedRule.getConditionValue());
                    rule.setConditionMin(updatedRule.getConditionMin());
                    rule.setConditionMax(updatedRule.getConditionMax());
                    rule.setAdviceContent(updatedRule.getAdviceContent());
                    rule.setAdviceType(updatedRule.getAdviceType());
                    rule.setEnabled(updatedRule.getEnabled());
                    rule.setRuleOrder(updatedRule.getRuleOrder());
                    rule.setUpdatedAt(LocalDateTime.now());
                    AdviceRule saved = adviceRuleRepository.save(rule);
                    refreshCache();
                    logger.info("更新建议规则: id={}", id);
                    return saved;
                })
                .orElseThrow(() -> new IllegalArgumentException("规则不存在: " + id));
    }

    public boolean enableRule(Long id) {
        return adviceRuleRepository.findById(id)
                .map(rule -> {
                    rule.setEnabled(true);
                    rule.setUpdatedAt(LocalDateTime.now());
                    adviceRuleRepository.save(rule);
                    refreshCache();
                    logger.info("启用建议规则: id={}", id);
                    return true;
                })
                .orElse(false);
    }

    public boolean disableRule(Long id) {
        return adviceRuleRepository.findById(id)
                .map(rule -> {
                    rule.setEnabled(false);
                    rule.setUpdatedAt(LocalDateTime.now());
                    adviceRuleRepository.save(rule);
                    refreshCache();
                    logger.info("禁用建议规则: id={}", id);
                    return true;
                })
                .orElse(false);
    }

    public void deleteRule(Long id) {
        adviceRuleRepository.deleteById(id);
        ruleCache.remove(id);
        refreshCache();
        logger.info("删除建议规则: id={}", id);
    }

    public void refreshCache() {
        loadRulesToCache();
        logger.info("建议规则缓存已刷新");
    }

    public boolean isRulesEnabled() {
        return rulesEnabled;
    }

    public boolean isUseCustomRules() {
        return useCustomRules;
    }

    public static class AdviceRuleMatchResult {
        private final AdviceRule rule;
        private final String adviceContent;

        public AdviceRuleMatchResult(AdviceRule rule, String adviceContent) {
            this.rule = rule;
            this.adviceContent = adviceContent;
        }

        public AdviceRule getRule() { return rule; }
        public String getAdviceContent() { return adviceContent; }
        public String getPriority() { return rule.getPriority(); }
        public String getAdviceType() { return rule.getAdviceType(); }
    }
}
