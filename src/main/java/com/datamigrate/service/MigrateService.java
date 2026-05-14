package com.datamigrate.service;

import com.datamigrate.common.TaskStatus;
import com.datamigrate.config.DataSourceConfig;
import com.datamigrate.entity.MappingRule;
import com.datamigrate.entity.MigrateProgress;
import com.datamigrate.entity.MigrateTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class MigrateService {

    private final DataSourceConfig dataSourceConfig;
    private final ProgressService progressService;
    private final TaskService taskService;
    private final LogService logService;
    private final FailService failService;
    private final StatService statService;
    private final VerifyService verifyService;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Boolean> runningTasks = new ConcurrentHashMap<>();

    public boolean startMigrate(String taskId) {
        if (runningTasks.containsKey(taskId) && runningTasks.get(taskId)) {
            logService.logMigrate(taskId, "任务已经在运行中");
            return false;
        }

        Optional<MigrateTask> taskOpt = taskService.getTask(taskId);
        if (!taskOpt.isPresent()) {
            logService.logMigrateError(taskId, "任务不存在: " + taskId, null);
            return false;
        }

        MigrateTask task = taskOpt.get();
        runningTasks.put(taskId, true);

        new Thread(() -> executeMigrate(task)).start();
        
        return true;
    }

    public boolean stopMigrate(String taskId) {
        runningTasks.remove(taskId);
        taskService.updateTaskStatus(taskId, TaskStatus.CANCELLED);
        logService.logMigrate(taskId, "迁移任务已停止");
        dataSourceConfig.closeConnections(taskId);
        return true;
    }

    private void executeMigrate(MigrateTask task) {
        String taskId = task.getTaskId();
        Connection sourceConn = null;
        Connection targetConn = null;
        
        try {
            taskService.updateTaskStatus(taskId, TaskStatus.RUNNING);
            logService.logMigrate(taskId, "开始执行迁移任务");
            statService.createStat(taskId);

            sourceConn = dataSourceConfig.getSourceConnection(task);
            targetConn = dataSourceConfig.getTargetConnection(task);
            targetConn.setAutoCommit(false);

            long totalRecords = getTotalRecords(sourceConn, task);
            progressService.createProgress(taskId, totalRecords);
            logService.logMigrate(taskId, "源数据总记录数: " + totalRecords);

            List<MappingRule> mappingRules = taskService.getMappingRules(taskId);
            Map<String, String> fieldMapping = buildFieldMapping(mappingRules);

            long offset = 0;
            int batchSize = task.getBatchSize() != null ? task.getBatchSize() : 100;
            int maxRetries = task.getMaxRetryTimes() != null ? task.getMaxRetryTimes() : 3;
            String pkField = task.getPrimaryKeyField() != null ? task.getPrimaryKeyField() : "id";

            while (runningTasks.getOrDefault(taskId, false) && offset < totalRecords) {
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

                    try {
                        Map<String, Object> transformedRecord = transformRecord(record, fieldMapping, mappingRules);
                        
                        if (insertOrUpdateRecord(targetConn, task, transformedRecord, pkField)) {
                            batchSuccess++;
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
                progressService.incrementBatch(taskId, batchData.size(), batchSuccess, batchFail);
                statService.incrementBatch(taskId);
                
                Optional<MigrateProgress> progress = progressService.getProgress(taskId);
                progress.ifPresent(p -> statService.updateStatOnProgress(taskId, 
                    p.getTotalRecords(), p.getSuccessRecords(), p.getFailRecords()));

                logService.logMigrate(taskId, String.format("批次处理完成: 总数=%d, 成功=%d, 失败=%d, 进度=%d%%",
                    batchData.size(), batchSuccess, batchFail, 
                    progress.map(MigrateProgress::getProgressRate).orElse(0)));

                offset += batchSize;
            }

            if (runningTasks.getOrDefault(taskId, false)) {
                logService.logMigrate(taskId, "迁移执行完成");
                
                if (Boolean.TRUE.equals(task.getAutoVerify())) {
                    logService.logMigrate(taskId, "开始自动校验数据");
                    taskService.updateTaskStatus(taskId, TaskStatus.VERIFYING);
                    verifyService.verify(taskId);
                    taskService.updateTaskStatus(taskId, TaskStatus.VERIFIED);
                } else {
                    taskService.updateTaskStatus(taskId, TaskStatus.COMPLETED);
                }
                
                statService.completeStat(taskId);
            }

        } catch (Exception e) {
            log.error("迁移任务执行异常", e);
            logService.logMigrateError(taskId, "迁移执行异常: " + e.getMessage(), e);
            taskService.updateTaskStatus(taskId, TaskStatus.FAILED);
        } finally {
            runningTasks.remove(taskId);
            dataSourceConfig.closeConnections(taskId);
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
                    String columnName = metaData.getColumnName(i);
                    row.put(columnName, rs.getObject(i));
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

    private Map<String, Object> transformRecord(Map<String, Object> sourceRecord, 
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
                    value = applyTransformation(value, rule.get().getTransformation());
                }
                
                targetRecord.put(targetField, value);
            }
        }
        return targetRecord;
    }

    private Object applyTransformation(Object value, String transformation) {
        if (value == null) {
            return null;
        }
        
        switch (transformation.toLowerCase()) {
            case "uppercase":
                return value.toString().toUpperCase();
            case "lowercase":
                return value.toString().toLowerCase();
            case "trim":
                return value.toString().trim();
            default:
                return value;
        }
    }

    private boolean insertOrUpdateRecord(Connection conn, MigrateTask task, 
                                          Map<String, Object> record, String pkField) throws SQLException {
        if (record.isEmpty()) {
            return false;
        }

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

    private void handleRecordFail(String taskId, Map<String, Object> record, String pkField, 
                                   String reason, int maxRetries) {
        String recordKey = String.valueOf(record.get(pkField));
        failService.recordFail(taskId, recordKey, record, reason, maxRetries);
    }

    public boolean isTaskRunning(String taskId) {
        return runningTasks.getOrDefault(taskId, false);
    }
}
