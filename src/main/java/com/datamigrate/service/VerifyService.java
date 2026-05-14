package com.datamigrate.service;

import com.datamigrate.common.DiffType;
import com.datamigrate.common.VerifyStatus;
import com.datamigrate.config.DataSourceConfig;
import com.datamigrate.dto.VerifyResponse;
import com.datamigrate.entity.DiffRecord;
import com.datamigrate.entity.MappingRule;
import com.datamigrate.entity.MigrateTask;
import com.datamigrate.entity.VerifyRecord;
import com.datamigrate.repository.DiffRecordRepository;
import com.datamigrate.repository.VerifyRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class VerifyService {

    private final DataSourceConfig dataSourceConfig;
    private final TaskService taskService;
    private final LogService logService;
    private final StatService statService;
    private final VerifyRecordRepository verifyRecordRepository;
    private final DiffRecordRepository diffRecordRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public VerifyResponse verify(String taskId) {
        Optional<MigrateTask> taskOpt = taskService.getTask(taskId);
        if (!taskOpt.isPresent()) {
            logService.logVerify(taskId, "校验任务不存在: " + taskId);
            return null;
        }

        MigrateTask task = taskOpt.get();
        
        VerifyRecord verifyRecord = new VerifyRecord();
        verifyRecord.setVerifyId("verify_" + UUID.randomUUID().toString().substring(0, 8));
        verifyRecord.setTaskId(taskId);
        verifyRecord.setVerifyType("full");
        verifyRecord.setVerifyStatus(VerifyStatus.RUNNING);
        verifyRecord = verifyRecordRepository.save(verifyRecord);

        Connection sourceConn = null;
        Connection targetConn = null;

        try {
            logService.logVerify(taskId, "开始执行数据校验");
            sourceConn = dataSourceConfig.getSourceConnection(task);
            targetConn = dataSourceConfig.getTargetConnection(task);

            List<MappingRule> mappingRules = taskService.getMappingRules(taskId);
            Map<String, String> fieldMapping = buildFieldMapping(mappingRules);
            String pkField = task.getPrimaryKeyField() != null ? task.getPrimaryKeyField() : "id";

            Map<Object, Map<String, Object>> sourceData = readAllData(sourceConn, task, pkField);
            Map<Object, Map<String, Object>> targetData = readAllData(targetConn, task, fieldMapping, pkField);

            long totalVerified = 0;
            long matchCount = 0;
            long diffCount = 0;

            for (Map.Entry<Object, Map<String, Object>> entry : sourceData.entrySet()) {
                totalVerified++;
                Object key = entry.getKey();
                Map<String, Object> sourceRecord = entry.getValue();
                Map<String, Object> transformedSource = transformRecord(sourceRecord, fieldMapping, mappingRules);

                if (!targetData.containsKey(key)) {
                    diffCount++;
                    recordDiff(verifyRecord.getVerifyId(), taskId, String.valueOf(key), 
                        transformedSource, null, DiffType.MISSING_IN_TARGET, null);
                } else {
                    Map<String, Object> targetRecord = targetData.get(key);
                    List<String> diffFields = compareRecords(transformedSource, targetRecord);
                    
                    if (diffFields.isEmpty()) {
                        matchCount++;
                    } else {
                        diffCount++;
                        recordDiff(verifyRecord.getVerifyId(), taskId, String.valueOf(key),
                            transformedSource, targetRecord, DiffType.VALUE_DIFF, diffFields);
                    }
                }
            }

            for (Object key : targetData.keySet()) {
                if (!sourceData.containsKey(key)) {
                    diffCount++;
                    totalVerified++;
                    recordDiff(verifyRecord.getVerifyId(), taskId, String.valueOf(key),
                        null, targetData.get(key), DiffType.MISSING_IN_SOURCE, null);
                }
            }

            verifyRecord.setTotalVerified(totalVerified);
            verifyRecord.setMatchCount(matchCount);
            verifyRecord.setDiffCount(diffCount);
            verifyRecord.setVerifyStatus(VerifyStatus.COMPLETED);
            verifyRecord.setVerifiedAt(LocalDateTime.now());
            verifyRecordRepository.save(verifyRecord);

            statService.updateVerifyResult(taskId, totalVerified, matchCount);

            logService.logVerify(taskId, String.format("校验完成: 总计=%d, 匹配=%d, 差异=%d", 
                totalVerified, matchCount, diffCount));

        } catch (Exception e) {
            log.error("数据校验异常", e);
            logService.logVerify(taskId, "校验异常: " + e.getMessage());
            verifyRecord.setVerifyStatus(VerifyStatus.FAILED);
            verifyRecord.setErrorMessage(e.getMessage());
            verifyRecordRepository.save(verifyRecord);
        } finally {
            dataSourceConfig.closeConnections(taskId);
        }

        return buildVerifyResponse(verifyRecord);
    }

    private Map<String, String> buildFieldMapping(List<MappingRule> rules) {
        Map<String, String> mapping = new HashMap<>();
        for (MappingRule rule : rules) {
            mapping.put(rule.getSourceField(), rule.getTargetField());
        }
        return mapping;
    }

    private Map<Object, Map<String, Object>> readAllData(Connection conn, MigrateTask task, String pkField) 
            throws SQLException {
        return readAllData(conn, task, null, pkField);
    }

    private Map<Object, Map<String, Object>> readAllData(Connection conn, MigrateTask task, 
                                                          Map<String, String> fieldMapping, String pkField) 
            throws SQLException {
        Map<Object, Map<String, Object>> data = new LinkedHashMap<>();
        String sql;
        String table = task.getSourceTable();
        
        if (fieldMapping != null) {
            table = task.getTargetTable();
        }

        if (task.getSourceQuery() != null && !task.getSourceQuery().isEmpty() && fieldMapping == null) {
            sql = task.getSourceQuery();
        } else {
            sql = "SELECT * FROM " + table;
        }

        try (Statement stmt = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
             ResultSet rs = stmt.executeQuery(sql)) {
            
            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();
            List<String> columns = new ArrayList<>();
            for (int i = 1; i <= columnCount; i++) {
                columns.add(metaData.getColumnName(i));
            }

            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                Object pkValue = null;
                
                for (int i = 1; i <= columnCount; i++) {
                    String colName = columns.get(i - 1);
                    Object value = rs.getObject(i);
                    row.put(colName, value);
                    if (colName.equalsIgnoreCase(pkField)) {
                        pkValue = value;
                    }
                }
                
                if (pkValue != null) {
                    data.put(pkValue, row);
                }
            }
        }
        return data;
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

    private List<String> compareRecords(Map<String, Object> source, Map<String, Object> target) {
        List<String> diffFields = new ArrayList<>();
        
        for (String field : source.keySet()) {
            Object sourceVal = source.get(field);
            Object targetVal = target.get(field);
            
            if (!Objects.equals(sourceVal, targetVal)) {
                diffFields.add(field);
            }
        }
        
        return diffFields;
    }

    @Transactional
    public void recordDiff(String verifyId, String taskId, String recordKey,
                           Map<String, Object> sourceValue, Map<String, Object> targetValue,
                           DiffType diffType, List<String> diffFields) {
        DiffRecord diff = new DiffRecord();
        diff.setDiffId("diff_" + UUID.randomUUID().toString().substring(0, 8));
        diff.setVerifyId(verifyId);
        diff.setTaskId(taskId);
        diff.setRecordKey(recordKey);
        diff.setDiffType(diffType);
        
        try {
            if (sourceValue != null) {
                diff.setSourceValue(objectMapper.writeValueAsString(sourceValue));
            }
            if (targetValue != null) {
                diff.setTargetValue(objectMapper.writeValueAsString(targetValue));
            }
            if (diffFields != null && !diffFields.isEmpty()) {
                diff.setDiffFields(objectMapper.writeValueAsString(diffFields));
            }
        } catch (Exception e) {
            log.warn("序列化差异数据失败", e);
        }
        
        diffRecordRepository.save(diff);
        
        logService.logVerify(taskId, "发现差异: key=" + recordKey + ", type=" + diffType);
    }

    private VerifyResponse buildVerifyResponse(VerifyRecord record) {
        VerifyResponse.VerifyInfo info = new VerifyResponse.VerifyInfo();
        info.setVerifyId(record.getVerifyId());
        info.setVerifyType(record.getVerifyType());
        info.setVerifyStatus(record.getVerifyStatus());
        info.setTotalVerified(record.getTotalVerified());
        info.setMatchCount(record.getMatchCount());
        info.setDiffCount(record.getDiffCount());
        return new VerifyResponse(info);
    }

    public VerifyResponse getLatestVerifyResult(String taskId) {
        Optional<VerifyRecord> latest = verifyRecordRepository.findTopByTaskIdOrderByCreatedAtDesc(taskId);
        return latest.map(this::buildVerifyResponse).orElse(null);
    }

    public List<DiffRecord> getDiffRecords(String taskId) {
        return diffRecordRepository.findByTaskId(taskId);
    }
}
