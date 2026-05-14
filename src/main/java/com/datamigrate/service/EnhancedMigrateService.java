package com.datamigrate.service;

import com.datamigrate.common.TaskStatus;
import com.datamigrate.config.DataSourceConfig;
import com.datamigrate.config.ResumeConfig;
import com.datamigrate.entity.MappingRule;
import com.datamigrate.entity.MigrateProgress;
import com.datamigrate.entity.MigrateTask;
import com.datamigrate.expression.AdvancedTransformService;
import com.datamigrate.queue.PersistentWriteQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class EnhancedMigrateService {

    private final DataSourceConfig dataSourceConfig;
    private final ProgressService progressService;
    private final TaskService taskService;
    private final LogService logService;
    private final FailService failService;
    private final StatService statService;
    private final VerifyService verifyService;
    private final ConfigurableResumeManager resumeManager;
    private final AdvancedTransformService transformService;
    private final PersistentWriteQueue persistentQueue;
    
    private final Map<String, Boolean> runningTasks = new ConcurrentHashMap<>();

    public boolean startMigrate(String taskId) {
        if (runningTasks.containsKey(taskId) && runningTasks.get(taskId)) {
            logService.logMigrate(taskId, "增强迁移任务已经在运行中");
            return false;
        }

        Optional<MigrateTask> taskOpt = taskService.getTask(taskId);
        if (!taskOpt.isPresent()) {
            logService.logMigrateError(taskId, "任务不存在: " + taskId, null);
            return false;
        }

        MigrateTask task = taskOpt.get();
        runningTasks.put(taskId, true);

        new Thread(() -> executeEnhancedMigrate(task)).start();
        
        return true;
    }

    public boolean stopMigrate(String taskId) {
        runningTasks.remove(taskId);
        taskService.updateTaskStatus(taskId, TaskStatus.CANCELLED);
        logService.logMigrate(taskId, "增强迁移任务已停止");
        dataSourceConfig.closeConnections(taskId);
        return true;
    }

    private void executeEnhancedMigrate(MigrateTask task) {
        String taskId = task.getTaskId();
        Connection sourceConn = null;
        Connection targetConn = null;

        try {
            taskService.updateTaskStatus(taskId, TaskStatus.RUNNING);
            logService.logMigrate(taskId, "开始执行增强迁移任务");
            statService.createStat(taskId);

            resumeManager.initTask(taskId, task);
            ResumeConfig resumeConfig = resumeManager.getTaskConfig(taskId);
            logService.logMigrate(taskId, String.format("续传配置: strategy=%s, checkpointType=%s",
                resumeConfig.getResumeStrategy(), resumeConfig.getCheckpointType()));

            sourceConn = dataSourceConfig.getSourceConnection(task);
            targetConn = dataSourceConfig.getTargetConnection(task);
            targetConn.setAutoCommit(false);

            long totalRecords = getTotalRecords(sourceConn, task);
            long resumePosition = 0L;
            long migratedRecords = 0L;
            long successRecords = 0L;
            long failRecords = 0L;

            if (resumeConfig.isEnabled()) {
                ResumeManager.ResumeState resumeState = resumeManager.getResumeState(taskId);
                if (resumeState.isResumable()) {
                    resumePosition = resumeState.getResumePosition();
                    migratedRecords = resumeState.getMigratedRecords();
                    successRecords = resumeState.getSuccessRecords();
                    failRecords = resumeState.getFailRecords();
                    logService.logMigrate(taskId, String.format(
                        "从断点续传: position=%d, migrated=%d, success=%d, fail=%d",
                        resumePosition, migratedRecords, successRecords, failRecords));
                }
            }

            if (migratedRecords == 0) {
                progressService.createProgress(taskId, totalRecords);
            }

            List<MappingRule> mappingRules = taskService.getMappingRules(taskId);
            Map<String, String> fieldMapping = buildFieldMapping(mappingRules);
            int batchSize = task.getBatchSize() != null ? task.getBatchSize() : 100;
            int maxRetries = task.getMaxRetryTimes() != null ? task.getMaxRetryTimes() : 3;
            String pkField = task.getPrimaryKeyField() != null ? task.getPrimaryKeyField() : "id";

            long offset = resumePosition;
            int currentBatch = (int) (resumePosition / batchSize);
            long lastCheckpointTime = System.currentTimeMillis();

            while (runningTasks.getOrDefault(taskId, false) && offset < totalRecords) {
                int batchIndex = (int) (offset / batchSize);
                
                if (resumeManager.isBatchCompleted(taskId, batchIndex)) {
                    logService.logMigrate(taskId, String.format("跳过已完成批次: batch=%d", batchIndex));
                    offset += batchSize;
                    currentBatch++;
                    continue;
                }

                List<Map<String, Object>> batchData = readBatch(sourceConn, task, offset, batchSize, pkField);
                if (batchData.isEmpty()) {
                    break;
                }

                int batchSuccess = 0;
                int batchFail = 0;

                for (Map<String, Object> record : batchData) {
                    if (!runningTasks.getOrDefault(taskId, false)) {
                        break;
                    }

                    String recordKey = String.valueOf(record.get(pkField));

                    if (resumeConfig.isEnabled() && resumeManager.isProcessed(taskId, recordKey)) {
                        continue;
                    }

                    try {
                        Map<String, Object> transformedRecord = transformRecordWithExpression(
                            record, fieldMapping, mappingRules, transformService);

                        boolean writeSuccess;
                        
                        if (isAsyncWriteMode()) {
                            writeSuccess = submitAsyncWrite(taskId, transformedRecord, pkField, maxRetries);
                        } else {
                            writeSuccess = insertOrUpdateRecord(targetConn, task, transformedRecord, pkField);
                        }

                        if (writeSuccess) {
                            batchSuccess++;
                            if (resumeConfig.isEnabled()) {
                                resumeManager.recordProcessedWithCheckpoint(
                                    taskId, recordKey, migratedRecords + batchSuccess, recordKey);
                            }
                        } else {
                            batchFail++;
                            handleRecordFail(taskId, record, pkField, "写入失败", maxRetries);
                        }

                    } catch (Exception e) {
                        batchFail++;
                        handleRecordFail(taskId, record, pkField, e.getMessage(), maxRetries);
                        logService.logMigrateError(taskId, "记录处理异常", e);
                    }
                }

                targetConn.commit();

                migratedRecords += batchData.size();
                successRecords += batchSuccess;
                failRecords += batchFail;

                progressService.updateProgress(taskId, migratedRecords, successRecords, failRecords);
                statService.incrementBatch(taskId);
                statService.updateStatOnProgress(taskId, totalRecords, successRecords, failRecords);

                if (resumeConfig.isEnabled()) {
                    resumeManager.markBatchCompleted(taskId, batchIndex);
                    String lastBatchKey = String.valueOf(batchData.get(batchData.size() - 1).get(pkField));
                    resumeManager.saveCheckpoint(taskId, migratedRecords, lastBatchKey);
                }

                Optional<MigrateProgress> progress = progressService.getProgress(taskId);
                logService.logMigrate(taskId, String.format("批次处理完成: index=%d, 总数=%d, 成功=%d, 失败=%d, 进度=%d%%",
                    batchIndex, batchData.size(), batchSuccess, batchFail,
                    progress.map(MigrateProgress::getProgressRate).orElse(0)));

                offset += batchSize;
                currentBatch++;
            }

            if (runningTasks.getOrDefault(taskId, false)) {
                logService.logMigrate(taskId, "增强迁移执行完成");
                
                if (Boolean.TRUE.equals(task.getAutoVerify())) {
                    logService.logMigrate(taskId, "开始自动校验数据");
                    taskService.updateTaskStatus(taskId, TaskStatus.VERIFYING);
                    verifyService.verify(taskId);
                    taskService.updateTaskStatus(taskId, TaskStatus.VERIFIED);
                } else {
                    taskService.updateTaskStatus(taskId, TaskStatus.COMPLETED);
                }
                
                statService.completeStat(taskId);
                resumeManager.clearTaskState(taskId);
            }

        } catch (Exception e) {
            log.error("增强迁移任务执行异常", e);
            logService.logMigrateError(taskId, "增强迁移执行异常: " + e.getMessage(), e);
            taskService.updateTaskStatus(taskId, TaskStatus.FAILED);
        } finally {
            runningTasks.remove(taskId);
            dataSourceConfig.closeConnections(taskId);
        }
    }

    private Map<String, Object> transformRecordWithExpression(Map<String, Object> sourceRecord,
                                                                Map<String, String> fieldMapping,
                                                                List<MappingRule> rules,
                                                                AdvancedTransformService transformService) {
        if (hasExpressionRules(rules)) {
            return transformService.transformRecord(sourceRecord, rules);
        }
        return transformRecordSimple(sourceRecord, fieldMapping, rules);
    }

    private boolean hasExpressionRules(List<MappingRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return false;
        }
        for (MappingRule rule : rules) {
            String transformation = rule.getTransformation();
            if (transformation != null) {
                String lower = transformation.toLowerCase();
                if (lower.contains("if:") || lower.contains("default:") || 
                    lower.contains("json:") || lower.contains("|")) {
                    return true;
                }
            }
            if (rule.getSourceField() != null && rule.getSourceField().startsWith("expr:")) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> transformRecordSimple(Map<String, Object> sourceRecord,
                                                        Map<String, String> fieldMapping,
                                                        List<MappingRule> rules) {
        if (fieldMapping.isEmpty()) {
            return new LinkedHashMap<>(sourceRecord);
        }

        Map<String, Object> targetRecord = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : fieldMapping.entrySet()) {
            String sourceField = entry.getKey();
            String targetField = entry.getValue();
            
            if (sourceRecord.containsKey(sourceField)) {
                Object value = sourceRecord.get(sourceField);
                
                Optional<MappingRule> rule = rules.stream()
                    .filter(r -> r.getSourceField().equals(sourceField))
                    .findFirst();
                
                if (rule.isPresent() && rule.get().getTransformation() != null) {
                    value = applySimpleTransformation(value, rule.get().getTransformation());
                }
                
                targetRecord.put(targetField, value);
            }
        }
        return targetRecord;
    }

    private Object applySimpleTransformation(Object value, String transformation) {
        if (value == null) return null;
        String trans = transformation.trim().toLowerCase();
        switch (trans) {
            case "uppercase": return value.toString().toUpperCase();
            case "lowercase": return value.toString().toLowerCase();
            case "trim": return value.toString().trim();
            default: return value;
        }
    }

    private long getTotalRecords(Connection conn, MigrateTask task) throws SQLException {
        String sql;
        if (task.getSourceQuery() != null && !task.getSourceQuery().isEmpty()) {
            sql = "SELECT COUNT(*) FROM (" + task.getSourceQuery() + ") AS count_query";
        } else {
            sql = "SELECT COUNT(*) FROM " + task.getSourceTable();
        }
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        return 0;
    }

    private List<Map<String, Object>> readBatch(Connection conn, MigrateTask task,
                                                 long offset, int limit, String pkField) throws SQLException {
        List<Map<String, Object>> results = new ArrayList<>();
        String sql;
        
        if (task.getSourceQuery() != null && !task.getSourceQuery().isEmpty()) {
            sql = String.format("%s ORDER BY %s LIMIT %d OFFSET %d",
                task.getSourceQuery(), pkField, limit, offset);
        } else {
            sql = String.format("SELECT * FROM %s ORDER BY %s LIMIT %d OFFSET %d",
                task.getSourceTable(), pkField, limit, offset);
        }

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();
            
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.put(metaData.getColumnName(i), rs.getObject(i));
                }
                results.add(row);
            }
        }
        return results;
    }

    private Map<String, String> buildFieldMapping(List<MappingRule> rules) {
        Map<String, String> mapping = new HashMap<>();
        for (MappingRule rule : rules) {
            mapping.put(rule.getSourceField(), rule.getTargetField());
        }
        return mapping;
    }

    private boolean insertOrUpdateRecord(Connection conn, MigrateTask task,
                                          Map<String, Object> record, String pkField) throws SQLException {
        if (record.isEmpty()) return false;

        List<String> columns = new ArrayList<>(record.keySet());
        String targetTable = task.getTargetTable();
        String targetPk = pkField;

        Object pkValue = record.get(targetPk);
        if (pkValue != null) {
            String checkSql = String.format("SELECT 1 FROM %s WHERE %s = ?", targetTable, targetPk);
            try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                checkStmt.setObject(1, pkValue);
                try (ResultSet rs = checkStmt.executeQuery()) {
                    if (rs.next()) {
                        return updateRecord(conn, targetTable, record, columns, targetPk);
                    }
                }
            }
        }
        return insertRecord(conn, targetTable, record, columns);
    }

    private boolean insertRecord(Connection conn, String table, Map<String, Object> record,
                                  List<String> columns) throws SQLException {
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(table).append(" (");
        sql.append(String.join(", ", columns));
        sql.append(") VALUES (");
        String placeholders = columns.stream()
            .map(c -> "?")
            .reduce((a, b) -> a + ", " + b)
            .orElse("");
        sql.append(placeholders).append(")");

        try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < columns.size(); i++) {
                stmt.setObject(i + 1, record.get(columns.get(i)));
            }
            return stmt.executeUpdate() > 0;
        }
    }

    private boolean updateRecord(Connection conn, String table, Map<String, Object> record,
                                  List<String> columns, String pkField) throws SQLException {
        StringBuilder sql = new StringBuilder("UPDATE ").append(table).append(" SET ");
        List<String> updateColumns = new ArrayList<>();
        for (String col : columns) {
            if (!col.equalsIgnoreCase(pkField)) {
                updateColumns.add(col + " = ?");
            }
        }
        sql.append(String.join(", ", updateColumns));
        sql.append(" WHERE ").append(pkField).append(" = ?");

        try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            for (String col : columns) {
                if (!col.equalsIgnoreCase(pkField)) {
                    stmt.setObject(idx++, record.get(col));
                }
            }
            stmt.setObject(idx, record.get(pkField));
            return stmt.executeUpdate() > 0;
        }
    }

    private boolean submitAsyncWrite(String taskId, Map<String, Object> record,
                                      String pkField, int maxRetries) {
        AsyncWriteService.WriteTask writeTask = new AsyncWriteService.WriteTask(
            taskId, record, 0, maxRetries);
        return persistentQueue.offer(writeTask);
    }

    private boolean isAsyncWriteMode() {
        return persistentQueue != null;
    }

    private void handleRecordFail(String taskId, Map<String, Object> record, String pkField,
                                   String reason, int maxRetries) {
        String recordKey = String.valueOf(record.get(pkField));
        failService.recordFail(taskId, recordKey, record, reason, maxRetries);
    }

    public boolean isTaskRunning(String taskId) {
        return runningTasks.getOrDefault(taskId, false);
    }
}
