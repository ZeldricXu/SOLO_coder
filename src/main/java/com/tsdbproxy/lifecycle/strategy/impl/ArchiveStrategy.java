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
public class ArchiveStrategy implements LifecycleStrategy {

    private final LifecycleLogMapper lifecycleLogMapper;

    @Override
    public void execute(LifecyclePolicy policy) {
        log.info("执行数据归档: table={}, archiveDays={}, location={}",
                policy.getTableName(), policy.getArchiveDays(), policy.getArchiveLocation());

        LifecycleLog logEntity = new LifecycleLog();
        logEntity.setPolicyId(policy.getId());
        logEntity.setOperationType("archive");
        logEntity.setSourceTable(policy.getTableName());
        logEntity.setTargetTable(policy.getArchiveLocation());
        logEntity.setStartTime(LocalDateTime.now());

        try {
            long archivedRows = archiveData(policy);

            logEntity.setProcessedRows(archivedRows);
            logEntity.setStatus("success");
            logEntity.setEndTime(LocalDateTime.now());

            log.info("数据归档完成: 归档行数={}", archivedRows);
        } catch (Exception e) {
            log.error("数据归档失败", e);
            logEntity.setStatus("failed");
            logEntity.setErrorMessage(e.getMessage());
            logEntity.setEndTime(LocalDateTime.now());
        }

        lifecycleLogMapper.insert(logEntity);
    }

    private long archiveData(LifecyclePolicy policy) {
        log.info("模拟归档超过{}天的数据到: {}", policy.getArchiveDays(), policy.getArchiveLocation());
        return 5000L;
    }

    @Override
    public String getOperationType() {
        return "archive";
    }
}
