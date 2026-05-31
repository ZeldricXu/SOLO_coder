package com.cdcsync.quality.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cdcsync.common.exception.BusinessException;
import com.cdcsync.common.service.AbstractBaseService;
import com.cdcsync.quality.domain.QualityCheckResult;
import com.cdcsync.quality.domain.QualityRule;
import com.cdcsync.quality.executor.RuleExecutor;
import com.cdcsync.quality.mapper.QualityRuleMapper;
import com.cdcsync.quality.service.QualityCheckResultService;
import com.cdcsync.quality.service.QualityRuleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class QualityRuleServiceImpl extends AbstractBaseService<QualityRule, String, QualityRuleMapper>
        implements QualityRuleService {

    private final List<RuleExecutor> executors;
    private final QualityCheckResultService checkResultService;

    public QualityRuleServiceImpl(QualityRuleMapper mapper, List<RuleExecutor> executors, QualityCheckResultService checkResultService) {
        super(mapper);
        this.executors = executors;
        this.checkResultService = checkResultService;
    }

    @Override
    protected void setId(QualityRule entity, String id) {
        entity.setId(id);
    }

    @Override
    protected String getId(QualityRule entity) {
        return entity.getId();
    }

    @Override
    public QualityCheckResult executeRule(String ruleId) {
        QualityRule rule = findById(ruleId);
        if (rule == null) {
            throw new BusinessException("Quality rule not found: " + ruleId);
        }

        RuleExecutor executor = findExecutor(rule.getRuleType());
        if (executor == null) {
            throw new BusinessException("No executor found for rule type: " + rule.getRuleType());
        }

        QualityCheckResult result = executor.execute(rule);
        checkResultService.create(result);

        rule.setLastCheckAt(LocalDateTime.now());
        rule.setLastCheckResult(result.getResultStatus());
        update(rule);

        log.info("Rule executed: id={}, name={}, status={}", ruleId, rule.getName(), result.getResultStatus());
        return result;
    }

    @Override
    public List<QualityCheckResult> executeAllRules() {
        List<QualityRule> rules = findAll();
        List<QualityCheckResult> results = new ArrayList<>();

        for (QualityRule rule : rules) {
            if (rule.getEnabled() != null && rule.getEnabled() == 1) {
                try {
                    QualityCheckResult result = executeRule(rule.getId());
                    results.add(result);
                } catch (Exception e) {
                    log.error("Failed to execute rule: {}", rule.getName(), e);
                }
            }
        }

        log.info("All rules executed: total={}, success={}", rules.size(), results.size());
        return results;
    }

    @Override
    public void enableRule(String ruleId) {
        QualityRule rule = findById(ruleId);
        if (rule == null) {
            throw new BusinessException("Quality rule not found: " + ruleId);
        }
        rule.setEnabled(1);
        update(rule);
        log.info("Rule enabled: id={}, name={}", ruleId, rule.getName());
    }

    @Override
    public void disableRule(String ruleId) {
        QualityRule rule = findById(ruleId);
        if (rule == null) {
            throw new BusinessException("Quality rule not found: " + ruleId);
        }
        rule.setEnabled(0);
        update(rule);
        log.info("Rule disabled: id={}, name={}", ruleId, rule.getName());
    }

    private RuleExecutor findExecutor(String ruleType) {
        for (RuleExecutor executor : executors) {
            if (executor.supports(ruleType)) {
                return executor;
            }
        }
        return null;
    }

    @Override
    public List<QualityRule> findAll() {
        return mapper.selectList(new QueryWrapper<>());
    }
}
