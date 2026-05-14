package com.datamigrate.service;

import com.datamigrate.common.DiffType;
import com.datamigrate.common.VerifyStatus;
import com.datamigrate.config.DataSourceConfig;
import com.datamigrate.entity.MappingRule;
import com.datamigrate.entity.MigrateTask;
import com.datamigrate.entity.VerifyRecord;
import com.datamigrate.repository.VerifyRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConcurrentVerifyService {

    private final DataSourceConfig dataSourceConfig;
    private final TaskService taskService;
    private final LogService logService;
    private final StatService statService;
    private final VerifyRecordRepository verifyRecordRepository;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    private final int DEFAULT_THREAD_COUNT = Runtime.getRuntime().availableProcessors();
    private final int DEFAULT_BATCH_SIZE = 1000;
    private final ExecutorService verifyExecutor = 
        Executors.newFixedThreadPool(DEFAULT_THREAD_COUNT);

    public static class BatchTask {
        private final int batchIndex;
        private final long startId;
        private final long endId;
        private final List<Map<String, Object>> sourceBatch;
        private final List<Map<String, Object>> targetBatch;

        public BatchTask(int batchIndex, long startId, long endId,
                          List<Map<String, Object>> sourceBatch,
                          List<Map<String, Object>> targetBatch) {
            this.batchIndex = batchIndex;
            this.startId = startId;
            this.endId = endId;
            this.sourceBatch = sourceBatch;
            this.targetBatch = targetBatch;
        }

        public int getBatchIndex() { return batchIndex; }
        public long getStartId() { return startId; }
        public long getEndId() { return endId; }
        public List<Map<String, Object>> getSourceBatch() { return sourceBatch; }
        public List<Map<String, Object>> getTargetBatch() { return targetBatch; }
    }

    public static class BatchResult {
        private final int batchIndex;
        private final long totalVerified;
        private final long matchCount;
        private final long diffCount;
        private final List<DiffInfo> diffs;
        private final boolean success;
        private final String errorMessage;

        public BatchResult(int batchIndex, long totalVerified, long matchCount, 
                            long diffCount, List<DiffInfo> diffs) {
            this.batchIndex = batchIndex;
            this.totalVerified = totalVerified;
            this.matchCount = matchCount;
            this.diffCount = diffCount;
            this.diffs = diffs;
            this.success = true;
            this.errorMessage = null;
        }

        public BatchResult(int batchIndex, String errorMessage) {
            this.batchIndex = batchIndex;
            this.totalVerified = 0;
            this.matchCount = 0;
            this.diffCount = 0;
            this.diffs = Collections.emptyList();
            this.success = false;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess() { return success; }
        public int getBatchIndex() { return batchIndex; }
        public long getTotalVerified() { return totalVerified; }
        public long getMatchCount() { return matchCount; }
        public long getDiffCount() { return diffCount; }
        public List<DiffInfo> getDiffs() { return diffs; }
    }

    public static class DiffInfo {
        private final String recordKey;
        private final DiffType diffType;
        private final List<String> diffFields;
        private final Map<String, Object> sourceValue;
        private final Map<String, Object> targetValue;

        public DiffInfo(String recordKey, DiffType diffType, List<String> diffFields,
                         Map<String, Object> sourceValue, Map<String, Object> targetValue) {
            this.recordKey = recordKey;
            this.diffType = diffType;
            this.diffFields = diffFields;
            this.sourceValue = sourceValue;
            this.targetValue = targetValue;
        }

        public String getRecordKey() { return recordKey; }
        public DiffType getDiffType() { return diffType; }
    }

    @Transactional
    public VerifyRecord verifyConcurrent(String taskId) {
        return verifyConcurrent(taskId, DEFAULT_THREAD_COUNT, DEFAULT_BATCH_SIZE);
    }

    @Transactional
    public VerifyRecord verifyConcurrent(String taskId, int threadCount, int batchSize) {
        Optional<MigrateTask> taskOpt = taskService.getTask(taskId);
        if (!taskOpt.isPresent()) {
            log.error("校验任务不存在: taskId={}", taskId);
            return null;
        }

        MigrateTask task = taskOpt.get();

        VerifyRecord verifyRecord = new VerifyRecord();
        verifyRecord.setVerifyId("verify_" + UUID.randomUUID().toString().substring(0, 8));
        verifyRecord.setTaskId(taskId);
        verifyRecord.setVerifyType("concurrent");
        verifyRecord.setVerifyStatus(VerifyStatus.RUNNING);
        verifyRecord = verifyRecordRepository.save(verifyRecord);

        logService.logVerify(taskId, String.format("开始并发校验: 线程数=%d, 批次大小=%d", threadCount, batchSize));
        long startTime = System.currentTimeMillis();

        try {
            List<MappingRule> mappingRules = taskService.getMappingRules(taskId);
            Map<String, String> fieldMapping = buildFieldMapping(mappingRules);
            String pkField = task.getPrimaryKeyField() != null ? task.getPrimaryKeyField() : "id";

            List<BatchTask> batchTasks = prepareBatchTasks(task, batchSize, mappingRules, pkField);
            log.info("准备执行并发校验: 总批次={}", batchTasks.size());

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            List<Future<BatchResult>> futures = new ArrayList<>();

            for (BatchTask batchTask : batchTasks) {
                Future<BatchResult> future = executor.submit(() -> 
                    verifySingleBatch(batchTask, fieldMapping, mappingRules, pkField));
                futures.add(future);
            }

            AtomicLong totalVerified = new AtomicLong(0);
            AtomicLong matchCount = new AtomicLong(0);
            AtomicLong diffCount = new AtomicLong(0);
            List<DiffInfo> allDiffs = Collections.synchronizedList(new ArrayList<>());

            for (Future<BatchResult> future : futures) {
                try {
                    BatchResult result = future.get(5, TimeUnit.MINUTES);
                    if (result.isSuccess()) {
                        totalVerified.addAndGet(result.getTotalVerified());
                        matchCount.addAndGet(result.getMatchCount());
                        diffCount.addAndGet(result.getDiffCount());
                        allDiffs.addAll(result.getDiffs());
                    } else {
                        log.error("批次校验失败: batchIndex={}, error={}", result.getBatchIndex(), result.errorMessage);
                    }
                } catch (Exception e) {
                    log.error("获取批次结果失败", e);
                }
            }

            executor.shutdown();

            long elapsedTime = System.currentTimeMillis() - startTime;
            logService.logVerify(taskId, String.format(
                "并发校验完成: 总记录=%d, 匹配=%d, 差异=%d, 耗时=%dms",
                totalVerified.get(), matchCount.get(), diffCount.get(), elapsedTime));

            verifyRecord.setTotalVerified(totalVerified.get());
            verifyRecord.setMatchCount(matchCount.get());
            verifyRecord.setDiffCount(diffCount.get());
            verifyRecord.setVerifyStatus(VerifyStatus.COMPLETED);
            verifyRecord.setVerifiedAt(LocalDateTime.now());

            statService.updateVerifyResult(taskId, totalVerified.get(), matchCount.get());

            return verifyRecordRepository.save(verifyRecord);

        } catch (Exception e) {
            log.error("并发校验异常", e);
            verifyRecord.setVerifyStatus(VerifyStatus.FAILED);
            verifyRecord.setErrorMessage(e.getMessage());
            return verifyRecordRepository.save(verifyRecord);
        }
    }

    private List<BatchTask> prepareBatchTasks(MigrateTask task, int batchSize,
                                               List<MappingRule> mappingRules, String pkField) {
        List<BatchTask> tasks = new ArrayList<>();
        
        Connection sourceConn = null;
        Connection targetConn = null;
        
        try {
            sourceConn = dataSourceConfig.getSourceConnection(task);
            targetConn = dataSourceConfig.getTargetConnection(task);

            long totalRecords = getTotalRecords(sourceConn, task);
            Map<String, String> fieldMapping = buildFieldMapping(mappingRules);

            for (long start = 0; start < totalRecords; start += batchSize) {
                long end = Math.min(start + batchSize, totalRecords);
                int batchIndex = (int) (start / batchSize);

                List<Map<String, Object>> sourceBatch = readBatch(sourceConn, task, start, batchSize, pkField);
                List<Map<String, Object>> targetBatch = readTargetBatch(targetConn, task, start, batchSize, pkField);

                tasks.add(new BatchTask(batchIndex, start, end, sourceBatch, targetBatch));
            }

        } catch (Exception e) {
            log.error("准备批次任务失败", e);
        } finally {
            dataSourceConfig.closeConnections(task.getTaskId());
        }

        return tasks;
    }

    private BatchResult verifySingleBatch(BatchTask task, Map<String, String> fieldMapping,
                                           List<MappingRule> rules, String pkField) {
        try {
            Map<Object, Map<String, Object>> sourceMap = toKeyMap(task.getSourceBatch(), "id");
            Map<Object, Map<String, Object>> targetMap = toKeyMap(task.getTargetBatch(), "user_id");

            long matchCount = 0;
            long diffCount = 0;
            List<DiffInfo> diffs = new ArrayList<>();
            Set<Object> processedKeys = new HashSet<>();

            for (Map.Entry<Object, Map<String, Object>> entry : sourceMap.entrySet()) {
                Object key = entry.getKey();
                processedKeys.add(key);
                Map<String, Object> sourceRecord = entry.getValue();
                Map<String, Object> transformedSource = transformRecord(sourceRecord, fieldMapping, rules);

                if (!targetMap.containsKey(key)) {
                    diffCount++;
                    diffs.add(new DiffInfo(String.valueOf(key), DiffType.MISSING_IN_TARGET,
                        null, transformedSource, null));
                } else {
                    Map<String, Object> targetRecord = targetMap.get(key);
                    List<String> diffFields = compareRecords(transformedSource, targetRecord);
                    if (diffFields.isEmpty()) {
                        matchCount++;
                    } else {
                        diffCount++;
                        diffs.add(new DiffInfo(String.valueOf(key), DiffType.VALUE_DIFF,
                            diffFields, transformedSource, targetRecord));
                    }
                }
            }

            for (Object key : targetMap.keySet()) {
                if (!processedKeys.contains(key)) {
                    diffCount++;
                    diffs.add(new DiffInfo(String.valueOf(key), DiffType.MISSING_IN_SOURCE,
                        null, null, targetMap.get(key)));
                }
            }

            return new BatchResult(task.getBatchIndex(), sourceMap.size(), matchCount, diffCount, diffs);

        } catch (Exception e) {
            log.error("单批次校验异常: batchIndex={}", task.getBatchIndex(), e);
            return new BatchResult(task.getBatchIndex(), e.getMessage());
        }
    }

    private Map<String, String> buildFieldMapping(List<MappingRule> rules) {
        Map<String, String> mapping = new HashMap<>();
        for (MappingRule rule : rules) {
            mapping.put(rule.getSourceField(), rule.getTargetField());
        }
        return mapping;
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

    private List<Map<String, Object>> readTargetBatch(Connection conn, MigrateTask task,
                                                        long offset, int limit, String pkField) throws SQLException {
        String sql = String.format("SELECT * FROM %s ORDER BY user_id LIMIT %d OFFSET %d",
            task.getTargetTable(), limit, offset);

        List<Map<String, Object>> results = new ArrayList<>();
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

    private Map<Object, Map<String, Object>> toKeyMap(List<Map<String, Object>> records, String keyField) {
        Map<Object, Map<String, Object>> map = new LinkedHashMap<>();
        for (Map<String, Object> record : records) {
            Object key = record.get(keyField);
            if (key != null) {
                map.put(key, record);
            }
        }
        return map;
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
                targetRecord.put(targetField, value);
            }
        }
        return targetRecord;
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

    public void shutdown() {
        verifyExecutor.shutdown();
    }
}
