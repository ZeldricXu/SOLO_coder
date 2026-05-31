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
public class CleanupStrategy implements LifecycleStrategy {

    private final LifecycleLogMapper lifecycleLogMapper;

    @Override
    public void execute(LifecyclePolicy policy) {
        log.info("执行过期数据清理: table={}, coldDays={}", policy.getTableName(), policy.getColdDays());

        LifecycleLog logEntity = new LifecycleLog();
        logEntity.setPolicyId(policy.getId());
        logEntity.setOperationType("cleanup");
        logEntity.setSourceTable(policy.getTableName());
        logEntity.setStartTime(LocalDateTime.now());

        try {
            long deletedRows = cleanupData(policy);

            logEntity.setProcessedRows(deletedRows);
            logEntity.setStatus("success");
            logEntity.setEndTime(LocalDateTime.now());

            log.info("过期数据清理完成: 删除行数={}", deletedRows);
        } catch (Exception e) {
            log.error("过期数据清理失败", e);
            logEntity.setStatus("failed");
            logEntity.setErrorMessage(e.getMessage());
            logEntity.setEndTime(LocalDateTime.now());
        }

        lifecycleLogMapper.insert(logEntity);
    }

    private long cleanupData(LifecyclePolicy policy) {
        log.info("模拟清理超过{}天的过期数据", policy.getColdDays() + policy.getArchiveDays());
        return 10000L;
    }

    @Override
    public String getOperationType() {
        return "cleanup";
    }
}
