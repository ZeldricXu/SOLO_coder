package com.monitoring.dal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.monitoring.persistence.entity.AlertHistoryDO;
import com.monitoring.persistence.mapper.AlertHistoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class AlertHistoryRepository {

    private final AlertHistoryMapper alertHistoryMapper;

    public List<AlertHistoryDO> findByRuleId(String ruleId) {
        return alertHistoryMapper.selectList(
                new LambdaQueryWrapper<AlertHistoryDO>()
                        .eq(AlertHistoryDO::getRuleId, ruleId)
                        .orderByDesc(AlertHistoryDO::getStartedAt)
        );
    }

    public List<AlertHistoryDO> findActiveAlerts() {
        return alertHistoryMapper.selectList(
                new LambdaQueryWrapper<AlertHistoryDO>()
                        .eq(AlertHistoryDO::getStatus, "firing")
                        .isNull(AlertHistoryDO::getResolvedAt)
        );
    }

    public Optional<AlertHistoryDO> findByAlertId(String alertId) {
        return Optional.ofNullable(alertHistoryMapper.selectOne(
                new LambdaQueryWrapper<AlertHistoryDO>()
                        .eq(AlertHistoryDO::getAlertId, alertId)
        ));
    }

    public int save(AlertHistoryDO alertHistoryDO) {
        return alertHistoryMapper.insert(alertHistoryDO);
    }

    public int update(AlertHistoryDO alertHistoryDO) {
        return alertHistoryMapper.updateById(alertHistoryDO);
    }

    public int resolveAlert(String alertId, Instant resolvedAt) {
        AlertHistoryDO alertHistoryDO = new AlertHistoryDO();
        alertHistoryDO.setStatus("resolved");
        alertHistoryDO.setResolvedAt(resolvedAt);
        return alertHistoryMapper.update(alertHistoryDO,
                new LambdaQueryWrapper<AlertHistoryDO>()
                        .eq(AlertHistoryDO::getAlertId, alertId)
        );
    }
}
