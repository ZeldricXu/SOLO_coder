package com.monitoring.dal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.monitoring.persistence.entity.AlertRuleDO;
import com.monitoring.persistence.mapper.AlertRuleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class AlertRuleRepository {

    private final AlertRuleMapper alertRuleMapper;

    public List<AlertRuleDO> findAllEnabled() {
        return alertRuleMapper.selectList(
                new LambdaQueryWrapper<AlertRuleDO>()
                        .eq(AlertRuleDO::getEnabled, true)
        );
    }

    public List<AlertRuleDO> findByNamespace(String namespace) {
        return alertRuleMapper.selectList(
                new LambdaQueryWrapper<AlertRuleDO>()
                        .eq(AlertRuleDO::getNamespace, namespace)
        );
    }

    public Optional<AlertRuleDO> findByRuleId(String ruleId) {
        return Optional.ofNullable(alertRuleMapper.selectOne(
                new LambdaQueryWrapper<AlertRuleDO>()
                        .eq(AlertRuleDO::getRuleId, ruleId)
        ));
    }

    public int save(AlertRuleDO alertRuleDO) {
        return alertRuleMapper.insert(alertRuleDO);
    }

    public int update(AlertRuleDO alertRuleDO) {
        return alertRuleMapper.updateById(alertRuleDO);
    }

    public int deleteByRuleId(String ruleId) {
        return alertRuleMapper.delete(
                new LambdaQueryWrapper<AlertRuleDO>()
                        .eq(AlertRuleDO::getRuleId, ruleId)
        );
    }
}
