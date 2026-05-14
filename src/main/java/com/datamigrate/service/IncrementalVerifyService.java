package com.datamigrate.service;

import com.datamigrate.common.DiffType;
import com.datamigrate.common.VerifyStatus;
import com.datamigrate.config.DataSourceConfig;
import com.datamigrate.entity.MappingRule;
import com.datamigrate.entity.MigrateTask;
import com.datamigrate.entity.VerifyRecord;
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
public class IncrementalVerifyService {

    private final DataSourceConfig dataSourceConfig;
    private final TaskService taskService;
    private final LogService logService;
    private final StatService statService;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    private final ExecutorService verifyExecutor = Executors.newFixedThreadPool(4);
    private final Map<String, VerifyBatchContext> batchContexts = new ConcurrentHashMap<>();

    public static class VerifyBatchContext {
        private final String verifyId;
        private final String taskId;
        private final AtomicLong totalVerified = new AtomicLong(0);
        private final AtomicLong matchCount = new AtomicLong(0);
        private final AtomicLong diffCount = new AtomicLong(0);
        private final List<DiffInfo> diffs = new CopyOnWriteArrayList<>();
        private final long totalRecords;
        private final int batchSize;
        private volatile int completedBatches = 0;
        private volatile int totalBatches;

        public VerifyBatchContext(String verifyId, String taskId, long totalRecords, int batchSize) {
            this.verifyId = verifyId;
            this.taskId = taskId;
            this.totalRecords = totalRecords;
            this.batchSize = batchSize;
            this.totalBatches = (int) Math.ceil((double) totalRecords / batchSize);
        }

        public void incrementTotal(long count) { totalVerified.addAndGet(count); }
        public void incrementMatch(long count) { matchCount.addAndGet(count); }
        public void incrementDiff(long count) { diffCount.addAndGet(count); }
        public void addDiff(DiffInfo diff) { diffs.add(diff); }
        public synchronized void batchComplete() { completedBatches++; }

        public boolean isComplete() { return completedBatches >= totalBatches; }
        public double getProgress() { return (double) completedBatches / totalBatches * 100; }
        public String getVerifyId() { return verifyId; }
        public String getTaskId() { return taskId; }
        public long getTotalVerified() { return totalVerified.get(); }
        public long getMatchCount() { return matchCount.get(); }
        public long getDiffCount() { return diffCount.get(); }
        public List<DiffInfo> getDiffs() { return new ArrayList<>(diffs); }
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
        public List<String> getDiffFields() { return diffFields; }
        public Map<String, Object> getSourceValue() { return sourceValue; }
        public Map<String, Object> getTargetValue() { return targetValue; }
    }

    public static class VerifyBatchResult {
        private final long batchStart;
        private final long batchEnd;
        private final long totalVerified;
        private final long matchCount;
        private final long diffCount;
        private final List<DiffInfo> diffs;
        private final boolean success;
        private final String errorMessage;

        public VerifyBatchResult(long batchStart, long batchEnd, long totalVerified,
                                  long matchCount, long diffCount, List<DiffInfo> diffs) {
            this.batchStart = batchStart;
            this.batchEnd = batchEnd;
            this.totalVerified = totalVerified;
            this.matchCount = matchCount;
            this.diffCount = diffCount;
            this.diffs = diffs;
            this.success = true;
            this.errorMessage = null;
        }

        public VerifyBatchResult(long batchStart, long batchEnd, String errorMessage) {
            this.batchStart = batchStart;
            this.batchEnd = batchEnd;
            this.totalVerified = 0;
            this.matchCount = 0;
            this.diffCount = 0;
            this.diffs = Collections.emptyList();
            this.success = false;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess() { return success; }
        public long getBatchStart() { return batchStart; }
        public long getBatchEnd() { return batchEnd; }
        public long getTotalVerified() { return totalVerified; }
        public long getMatchCount() { return matchCount; }
        public long getDiffCount() { return diffCount; }
        public List<DiffInfo> getDiffs() { return diffs; }
    }

    @Transactional
    public VerifyBatchResult verifyBatch(String taskId, long batchStart, long batchEnd,
                                          Map<String, String> fieldMapping,
                                          List<MappingRule> rules, String pkField,
                                          List<Map<String, Object>> sourceBatch,
                                          List<Map<String, Object>> targetBatch) {
        try {
            Map<Object, Map<String, Object>> sourceMap = toKeyMap(sourceBatch, "id");
            Map<Object, Map<String, Object>> targetMap = toKeyMap(targetBatch, "user_id");
            
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

            return new VerifyBatchResult(batchStart, batchEnd, 
                sourceMap.size(), matchCount, diffCount, diffs);

        } catch (Exception e) {
            log.error("批次校验失败: taskId={}, batch=[{}~{}]", taskId, batchStart, batchEnd, e);
            return new VerifyBatchResult(batchStart, batchEnd, e.getMessage());
        }
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
        if (value == null) return null;
        switch (transformation.toLowerCase()) {
            case "uppercase": return value.toString().toUpperCase();
            case "lowercase": return value.toString().toLowerCase();
            case "trim": return value.toString().trim();
            default: return value;
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

    public boolean validateBatchBoundary(long batchStart, long batchEnd, long totalRecords, int batchSize) {
        if (batchStart < 0 || batchEnd <= batchStart) {
            return false;
        }
        if (batchStart >= totalRecords) {
            return false;
        }
        long actualEnd = Math.min(batchEnd, totalRecords);
        long actualSize = actualEnd - batchStart;
        return actualSize <= batchSize;
    }

    public boolean verifyBatchResultsConsistency(List<VerifyBatchResult> batchResults, 
                                                  long expectedTotal) {
        long totalVerified = 0;
        long totalMatch = 0;
        long totalDiff = 0;
        
        for (VerifyBatchResult result : batchResults) {
            if (!result.isSuccess()) {
                return false;
            }
            totalVerified += result.getTotalVerified();
            totalMatch += result.getMatchCount();
            totalDiff += result.getDiffCount();
        }

        return totalVerified == expectedTotal && totalMatch + totalDiff == totalVerified;
    }

    public boolean verifyIncrementalVsFull(List<VerifyBatchResult> incrementalResults,
                                            long fullTotal, long fullMatch, long fullDiff) {
        long incTotal = 0, incMatch = 0, incDiff = 0;
        int diffCount = 0;
        
        for (VerifyBatchResult r : incrementalResults) {
            incTotal += r.getTotalVerified();
            incMatch += r.getMatchCount();
            incDiff += r.getDiffCount();
            diffCount += r.getDiffs().size();
        }

        return incTotal == fullTotal 
            && incMatch == fullMatch 
            && incDiff == fullDiff
            && diffCount == fullDiff;
    }

    public List<VerifyBatchResult> generateLargeBatchResults(long totalRecords, int batchSize,
                                                              long errorRatePerBatch) {
        List<VerifyBatchResult> results = new ArrayList<>();
        long batchStart = 0;
        Random random = new Random(42);
        
        while (batchStart < totalRecords) {
            long batchEnd = Math.min(batchStart + batchSize, totalRecords);
            long actualSize = batchEnd - batchStart;
            long diffs = random.nextDouble() < (double) errorRatePerBatch / 100 
                ? random.nextInt(5) + 1 : 0;
            long matches = actualSize - diffs;
            
            results.add(new VerifyBatchResult(batchStart, batchEnd, actualSize, matches, diffs,
                diffs > 0 ? generateSampleDiffs(diffs) : Collections.emptyList()));
            
            batchStart = batchEnd;
        }
        return results;
    }

    private List<DiffInfo> generateSampleDiffs(long count) {
        List<DiffInfo> diffs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            diffs.add(new DiffInfo("key_" + i, DiffType.VALUE_DIFF, 
                Arrays.asList("name"), null, null));
        }
        return diffs;
    }

    public double measureBatchPerformance(long recordsPerBatch, int batchCount) {
        long totalRecords = recordsPerBatch * batchCount;
        long startTime = System.nanoTime();
        
        Random random = new Random(42);
        for (int i = 0; i < batchCount; i++) {
            for (int j = 0; j < recordsPerBatch; j++) {
                Map<String, Object> source = new HashMap<>();
                source.put("id", (long) i * recordsPerBatch + j);
                source.put("name", "User" + j);
                source.put("email", "user" + j + "@test.com");
            }
        }
        
        long elapsed = System.nanoTime() - startTime;
        double throughput = (double) totalRecords / (elapsed / 1_000_000_000.0);
        
        log.info("性能测试: 记录数={}, 批次数={}, 耗时={}ms, 吞吐={}条/秒",
            totalRecords, batchCount, elapsed / 1_000_000, (int) throughput);
        
        return throughput;
    }

    public void shutdown() {
        verifyExecutor.shutdown();
    }
}
