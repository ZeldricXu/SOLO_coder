package com.metricplatform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.metricplatform.dto.DataLifecycleDTO;
import com.metricplatform.dto.LifecycleExecutionResult;
import com.metricplatform.entity.SysDataLifecycle;
import com.metricplatform.mapper.SysDataLifecycleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metricplatform.datasource.ReadOnly;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataLifecycleService extends ServiceImpl<SysDataLifecycleMapper, SysDataLifecycle> {

    private final JdbcTemplate jdbcTemplate;
    private final SysDataLifecycleMapper lifecycleMapper;

    @Value("${metric-platform.lifecycle.hot-data-days:7}")
    private int defaultHotDays;

    @Value("${metric-platform.lifecycle.warm-data-days:30}")
    private int defaultWarmDays;

    @Value("${metric-platform.lifecycle.cold-data-days:90}")
    private int defaultColdDays;

    @Value("${metric-platform.lifecycle.archive-enabled:true}")
    private boolean defaultArchiveEnabled;

    @Value("${metric-platform.lifecycle.cleanup-enabled:true}")
    private boolean defaultCleanupEnabled;

    @Transactional(rollbackFor = Exception.class)
    public SysDataLifecycle createLifecycle(DataLifecycleDTO dto) {
        if (dto.getWarmDays() <= dto.getHotDays() || dto.getColdDays() <= dto.getWarmDays()) {
            throw new IllegalArgumentException("数据生命周期天数必须递增: 热 < 温 < 冷");
        }

        SysDataLifecycle existing = this.getOne(new LambdaQueryWrapper<SysDataLifecycle>()
                .eq(SysDataLifecycle::getTableName, dto.getTableName()));

        if (existing != null) {
            throw new IllegalArgumentException("该表的生命周期配置已存在: " + dto.getTableName());
        }

        SysDataLifecycle lifecycle = new SysDataLifecycle();
        lifecycle.setLifecycleId("lifecycle_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        lifecycle.setTableName(dto.getTableName());
        lifecycle.setHotDays(dto.getHotDays());
        lifecycle.setWarmDays(dto.getWarmDays());
        lifecycle.setColdDays(dto.getColdDays());
        lifecycle.setArchiveEnabled(dto.getArchiveEnabled());
        lifecycle.setCleanupEnabled(dto.getCleanupEnabled());
        lifecycle.setArchiveTableSuffix(dto.getArchiveTableSuffix());

        this.save(lifecycle);
        log.info("已创建数据生命周期配置: {}", dto.getTableName());
        return lifecycle;
    }

    @Transactional(rollbackFor = Exception.class)
    public SysDataLifecycle updateLifecycle(String lifecycleId, DataLifecycleDTO dto) {
        SysDataLifecycle lifecycle = this.getById(lifecycleId);
        if (lifecycle == null) {
            throw new IllegalArgumentException("生命周期配置不存在: " + lifecycleId);
        }

        if (dto.getWarmDays() <= dto.getHotDays() || dto.getColdDays() <= dto.getWarmDays()) {
            throw new IllegalArgumentException("数据生命周期天数必须递增: 热 < 温 < 冷");
        }

        lifecycle.setHotDays(dto.getHotDays());
        lifecycle.setWarmDays(dto.getWarmDays());
        lifecycle.setColdDays(dto.getColdDays());
        lifecycle.setArchiveEnabled(dto.getArchiveEnabled());
        lifecycle.setCleanupEnabled(dto.getCleanupEnabled());
        lifecycle.setArchiveTableSuffix(dto.getArchiveTableSuffix());

        this.updateById(lifecycle);
        log.info("已更新数据生命周期配置: {}", dto.getTableName());
        return lifecycle;
    }

    @Scheduled(cron = "0 0 2 * * ?")
    public void scheduledLifecycleProcessing() {
        log.info("开始执行定时数据生命周期处理...");
        List<SysDataLifecycle> configs = this.list();
        for (SysDataLifecycle config : configs) {
            try {
                processLifecycle(config);
            } catch (Exception e) {
                log.error("处理数据生命周期失败: {}", config.getTableName(), e);
            }
        }
        log.info("定时数据生命周期处理完成");
    }

    @Async("taskExecutor")
    public void processLifecycleAsync(SysDataLifecycle config) {
        processLifecycle(config);
    }

    public List<LifecycleExecutionResult> processLifecycle(SysDataLifecycle config) {
        List<LifecycleExecutionResult> results = new ArrayList<>();

        if (config.getArchiveEnabled()) {
            LifecycleExecutionResult archiveResult = archiveWarmData(config);
            results.add(archiveResult);
        }

        if (config.getCleanupEnabled()) {
            LifecycleExecutionResult cleanupResult = cleanupColdData(config);
            results.add(cleanupResult);
        }

        return results;
    }

    @Transactional(rollbackFor = Exception.class)
    public LifecycleExecutionResult archiveWarmData(SysDataLifecycle config) {
        LocalDateTime startTime = LocalDateTime.now();
        String sourceTable = config.getTableName();
        String archiveTable = sourceTable + config.getArchiveTableSuffix();

        try {
            if (lifecycleMapper.tableExists(sourceTable) == 0) {
                return buildResult(sourceTable, "archive", 0, false,
                        "源表不存在: " + sourceTable, startTime);
            }

            createArchiveTableIfNotExists(sourceTable, archiveTable);

            String insertSql = String.format(
                    "INSERT INTO %s SELECT * FROM %s WHERE created_at < DATE_SUB(NOW(), INTERVAL %d DAY) " +
                            "AND created_at >= DATE_SUB(NOW(), INTERVAL %d DAY)",
                    archiveTable, sourceTable, config.getHotDays(), config.getWarmDays());

            int affectedRows = jdbcTemplate.update(insertSql);

            if (affectedRows > 0) {
                String deleteSql = String.format(
                        "DELETE FROM %s WHERE created_at < DATE_SUB(NOW(), INTERVAL %d DAY) " +
                                "AND created_at >= DATE_SUB(NOW(), INTERVAL %d DAY)",
                        sourceTable, config.getHotDays(), config.getWarmDays());
                jdbcTemplate.update(deleteSql);
            }

            config.setLastMigrateAt(LocalDateTime.now());
            config.setLastArchiveAt(LocalDateTime.now());
            this.updateById(config);

            log.info("数据归档完成: {} -> {}, 迁移 {} 行", sourceTable, archiveTable, affectedRows);
            return buildResult(sourceTable, "archive", affectedRows, true, null, startTime);

        } catch (Exception e) {
            log.error("数据归档失败: {}", sourceTable, e);
            return buildResult(sourceTable, "archive", 0, false, e.getMessage(), startTime);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public LifecycleExecutionResult cleanupColdData(SysDataLifecycle config) {
        LocalDateTime startTime = LocalDateTime.now();
        String sourceTable = config.getTableName();
        String archiveTable = sourceTable + config.getArchiveTableSuffix();

        try {
            long totalDeleted = 0;

            if (lifecycleMapper.tableExists(sourceTable) > 0) {
                String deleteHotSql = String.format(
                        "DELETE FROM %s WHERE created_at < DATE_SUB(NOW(), INTERVAL %d DAY)",
                        sourceTable, config.getColdDays());
                totalDeleted += jdbcTemplate.update(deleteHotSql);
            }

            if (lifecycleMapper.tableExists(archiveTable) > 0) {
                String deleteArchiveSql = String.format(
                        "DELETE FROM %s WHERE created_at < DATE_SUB(NOW(), INTERVAL %d DAY)",
                        archiveTable, config.getColdDays());
                totalDeleted += jdbcTemplate.update(deleteArchiveSql);
            }

            config.setLastCleanupAt(LocalDateTime.now());
            this.updateById(config);

            log.info("数据清理完成: {}, 删除 {} 行", sourceTable, totalDeleted);
            return buildResult(sourceTable, "cleanup", totalDeleted, true, null, startTime);

        } catch (Exception e) {
            log.error("数据清理失败: {}", sourceTable, e);
            return buildResult(sourceTable, "cleanup", 0, false, e.getMessage(), startTime);
        }
    }

    private void createArchiveTableIfNotExists(String sourceTable, String archiveTable) {
        if (lifecycleMapper.tableExists(archiveTable) == 0) {
            String createSql = String.format("CREATE TABLE %s LIKE %s", archiveTable, sourceTable);
            jdbcTemplate.update(createSql);
            log.info("已创建归档表: {}", archiveTable);
        }
    }

    private LifecycleExecutionResult buildResult(String tableName, String operation, long affectedRows,
                                                 boolean success, String errorMessage, LocalDateTime startTime) {
        LocalDateTime endTime = LocalDateTime.now();
        return LifecycleExecutionResult.builder()
                .tableName(tableName)
                .operation(operation)
                .affectedRows(affectedRows)
                .success(success)
                .errorMessage(errorMessage)
                .startTime(startTime)
                .endTime(endTime)
                .durationMs(java.time.Duration.between(startTime, endTime).toMillis())
                .build();
    }

    public long getAffectedRowsPreview(String tableName, int days) {
        return lifecycleMapper.countOlderThan(tableName, days);
    }

    public List<SysDataLifecycle> getAllLifecycles() {
        return this.list();
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean deleteLifecycle(String lifecycleId) {
        return this.removeById(lifecycleId);
    }
}
