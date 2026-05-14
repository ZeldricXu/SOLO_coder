package com.datamigrate.builder;

import com.datamigrate.common.TaskStatus;
import com.datamigrate.common.VerifyStatus;
import com.datamigrate.common.FailStatus;
import com.datamigrate.common.DiffType;
import com.datamigrate.common.LogLevel;
import com.datamigrate.common.LogType;
import com.datamigrate.entity.*;

import java.time.LocalDateTime;
import java.util.*;

public class TestDataBuilder {

    private static final Random RANDOM = new Random(42);

    public static MigrateTask createMigrateTask(String taskId) {
        MigrateTask task = new MigrateTask();
        task.setTaskId(taskId);
        task.setTaskName("测试迁移任务_" + taskId);
        task.setSourceType("mysql");
        task.setSourceHost("localhost");
        task.setSourcePort(3306);
        task.setSourceDatabase("source_db");
        task.setSourceUsername("root");
        task.setSourcePassword("password");
        task.setSourceTable("users");
        task.setSourceQuery("SELECT * FROM users");
        task.setTargetType("mysql");
        task.setTargetHost("localhost");
        task.setTargetPort(3307);
        task.setTargetDatabase("target_db");
        task.setTargetUsername("root");
        task.setTargetPassword("password");
        task.setTargetTable("target_users");
        task.setPrimaryKeyField("id");
        task.setBatchSize(100);
        task.setMaxRetryTimes(3);
        task.setAutoVerify(true);
        task.setStatus(TaskStatus.PENDING);
        task.setDescription("测试任务描述");
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        return task;
    }

    public static MigrateTask createMigrateTaskWithMappingRules(String taskId, List<MappingRule> rules) {
        MigrateTask task = createMigrateTask(taskId);
        task.setMappingRules(rules);
        return task;
    }

    public static List<MappingRule> createDefaultMappingRules() {
        List<MappingRule> rules = new ArrayList<>();
        rules.add(createMappingRule(1L, "id", "user_id", 1));
        rules.add(createMappingRule(2L, "name", "user_name", 2));
        rules.add(createMappingRule(3L, "email", "user_email", 3));
        rules.add(createMappingRule(4L, "created_at", "created_time", "uppercase", 4));
        return rules;
    }

    public static MappingRule createMappingRule(Long id, String sourceField, String targetField, int order) {
        return createMappingRule(id, sourceField, targetField, null, order);
    }

    public static MappingRule createMappingRule(Long id, String sourceField, String targetField, 
                                                 String transformation, int order) {
        MappingRule rule = new MappingRule();
        rule.setId(id);
        rule.setSourceField(sourceField);
        rule.setTargetField(targetField);
        rule.setTransformation(transformation);
        rule.setRuleOrder(order);
        return rule;
    }

    public static MigrateProgress createProgress(String taskId, long totalRecords) {
        MigrateProgress progress = new MigrateProgress();
        progress.setProgressId("progress_" + taskId);
        progress.setTaskId(taskId);
        progress.setTotalRecords(totalRecords);
        progress.setMigratedRecords(0L);
        progress.setSuccessRecords(0L);
        progress.setFailRecords(0L);
        progress.setProgressRate(0);
        progress.setCurrentBatch(0);
        progress.setCurrentPosition(0L);
        progress.setIsResumable(false);
        progress.setCreatedAt(LocalDateTime.now());
        progress.setUpdatedAt(LocalDateTime.now());
        return progress;
    }

    public static MigrateProgress createProgressAtPoint(String taskId, long totalRecords, 
                                                         long migrated, long success, long fail,
                                                         int currentBatch, String lastKey) {
        MigrateProgress progress = createProgress(taskId, totalRecords);
        progress.setMigratedRecords(migrated);
        progress.setSuccessRecords(success);
        progress.setFailRecords(fail);
        progress.setCurrentBatch(currentBatch);
        progress.setCurrentPosition(migrated);
        progress.setLastProcessedKey(lastKey);
        progress.setIsResumable(true);
        if (totalRecords > 0) {
            progress.setProgressRate((int) (migrated * 100 / totalRecords));
        }
        return progress;
    }

    public static VerifyRecord createVerifyRecord(String taskId, String verifyId) {
        VerifyRecord record = new VerifyRecord();
        record.setVerifyId(verifyId);
        record.setTaskId(taskId);
        record.setVerifyType("full");
        record.setVerifyStatus(VerifyStatus.PENDING);
        record.setTotalVerified(0L);
        record.setMatchCount(0L);
        record.setDiffCount(0L);
        record.setCreatedAt(LocalDateTime.now());
        return record;
    }

    public static VerifyRecord createCompleteVerifyRecord(String taskId, String verifyId,
                                                          long totalVerified, long matchCount, long diffCount) {
        VerifyRecord record = createVerifyRecord(taskId, verifyId);
        record.setVerifyStatus(VerifyStatus.COMPLETED);
        record.setTotalVerified(totalVerified);
        record.setMatchCount(matchCount);
        record.setDiffCount(diffCount);
        record.setVerifiedAt(LocalDateTime.now());
        return record;
    }

    public static DiffRecord createDiffRecord(String verifyId, String taskId, String recordKey,
                                               DiffType diffType) {
        DiffRecord diff = new DiffRecord();
        diff.setDiffId("diff_" + UUID.randomUUID().toString().substring(0, 8));
        diff.setVerifyId(verifyId);
        diff.setTaskId(taskId);
        diff.setRecordKey(recordKey);
        diff.setDiffType(diffType);
        diff.setDetectedAt(LocalDateTime.now());
        return diff;
    }

    public static FailRecord createFailRecord(String taskId, String recordKey, int maxRetries) {
        FailRecord fail = new FailRecord();
        fail.setFailId("fail_" + UUID.randomUUID().toString().substring(0, 8));
        fail.setTaskId(taskId);
        fail.setRecordKey(recordKey);
        fail.setRetryCount(0);
        fail.setMaxRetryTimes(maxRetries);
        fail.setStatus(FailStatus.PENDING_RETRY);
        fail.setNextRetryAt(LocalDateTime.now().plusSeconds(5));
        fail.setCreatedAt(LocalDateTime.now());
        return fail;
    }

    public static MigrateLog createMigrateLog(String taskId, LogType type, LogLevel level, String content) {
        MigrateLog log = new MigrateLog();
        log.setLogId("log_" + UUID.randomUUID().toString().substring(0, 12));
        log.setTaskId(taskId);
        log.setLogType(type);
        log.setLogLevel(level);
        log.setLogContent(content);
        log.setLogTime(LocalDateTime.now());
        return log;
    }

    public static MigrateStat createMigrateStat(String taskId) {
        MigrateStat stat = new MigrateStat();
        stat.setStatId("stat_" + taskId);
        stat.setTaskId(taskId);
        stat.setStartTime(LocalDateTime.now());
        return stat;
    }

    public static Map<String, Object> createSourceRecord(long id, String name, String email) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", id);
        record.put("name", name);
        record.put("email", email);
        record.put("created_at", LocalDateTime.now().toString());
        return record;
    }

    public static Map<String, Object> createTargetRecord(long id, String name, String email) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("user_id", id);
        record.put("user_name", name);
        record.put("user_email", email);
        return record;
    }

    public static List<Map<String, Object>> createBatchSourceRecords(long startId, int count) {
        List<Map<String, Object>> records = new ArrayList<>();
        for (long i = startId; i < startId + count; i++) {
            records.add(createSourceRecord(i, "User" + i, "user" + i + "@example.com"));
        }
        return records;
    }

    public static List<Map<String, Object>> createBatchTargetRecords(long startId, int count) {
        List<Map<String, Object>> records = new ArrayList<>();
        for (long i = startId; i < startId + count; i++) {
            records.add(createTargetRecord(i, "User" + i, "user" + i + "@example.com"));
        }
        return records;
    }

    public static List<Map<String, Object>> createBatchTargetRecordsWithDiff(long startId, int count, 
                                                                              long diffRecordId) {
        List<Map<String, Object>> records = new ArrayList<>();
        for (long i = startId; i < startId + count; i++) {
            if (i == diffRecordId) {
                Map<String, Object> diff = createTargetRecord(i, "ModifiedName" + i, "modified@example.com");
                records.add(diff);
            } else {
                records.add(createTargetRecord(i, "User" + i, "user" + i + "@example.com"));
            }
        }
        return records;
    }

    public static Map<String, String> createDefaultFieldMapping() {
        Map<String, String> mapping = new HashMap<>();
        mapping.put("id", "user_id");
        mapping.put("name", "user_name");
        mapping.put("email", "user_email");
        return mapping;
    }

    public static List<MigrateTask> createMultipleTasks(int count) {
        List<MigrateTask> tasks = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            tasks.add(createMigrateTask("task_" + i));
        }
        return tasks;
    }

    public static List<DiffRecord> createMultipleDiffRecords(String verifyId, String taskId, int count) {
        List<DiffRecord> diffs = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            DiffRecord diff = createDiffRecord(verifyId, taskId, "key_" + i, DiffType.VALUE_DIFF);
            diff.setDiffFields("[\"user_name\"]");
            diffs.add(diff);
        }
        return diffs;
    }

    public static List<FailRecord> createMultipleFailRecords(String taskId, int count, int maxRetries) {
        List<FailRecord> fails = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            fails.add(createFailRecord(taskId, "fail_key_" + i, maxRetries));
        }
        return fails;
    }

    public static Map<String, Object> createRandomSourceRecord() {
        long id = RANDOM.nextInt(1000000) + 1;
        return createSourceRecord(id, "RandomUser" + id, "random" + id + "@test.com");
    }
}
