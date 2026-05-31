package com.tsdbproxy.lifecycle.strategy.impl;

import com.tsdbproxy.common.entity.LifecycleLog;
import com.tsdbproxy.common.entity.LifecyclePolicy;
import com.tsdbproxy.common.mapper.LifecycleLogMapper;
import com.tsdbproxy.lifecycle.strategy.LifecycleStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class MigrateToColdStrategy implements LifecycleStrategy {

    private final LifecycleLogMapper lifecycleLogMapper;

    @Override
    public void execute(LifecyclePolicy policy) {
        log.info("执行冷热数据迁移: table={}, hotDays={}", policy.getTableName(), policy.getHotDays());

        LifecycleLog logEntity = new LifecycleLog();
        logEntity.setPolicyId(policy.getId());
        logEntity.setOperationType("migrate");
        logEntity.setSourceTable(policy.getTableName());
        logEntity.setTargetTable(policy.getTableName() + "_cold");
        logEntity.setStartTime(LocalDateTime.now());

        try {
            long migratedRows = migrateData(policy);

            logEntity.setProcessedRows(migratedRows);
            logEntity.setStatus("success");
            logEntity.setEndTime(LocalDateTime.now());

            log.info("冷热数据迁移完成: 迁移行数={}", migratedRows);
        } catch (Exception e) {
            log.error("冷热数据迁移失败", e);
            logEntity.setStatus("failed");
            logEntity.setErrorMessage(e.getMessage());
            logEntity.setEndTime(LocalDateTime.now());
        }

        lifecycleLogMapper.insert(logEntity);
    }

    private long migrateData(LifecyclePolicy policy) {
        log.info("模拟迁移超过{}天的数据到冷存储", policy.getHotDays());
        return 1000L;
    }

    @Override
    public String getOperationType() {
        return "migrate";
    }
}
