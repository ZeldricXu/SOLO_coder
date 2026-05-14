package com.datamigrate.service;

import com.datamigrate.builder.TestDataBuilder;
import com.datamigrate.common.DiffType;
import com.datamigrate.entity.MappingRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("增量校验比对服务测试")
class IncrementalVerifyServiceTest {

    @InjectMocks
    private IncrementalVerifyService verifyService;

    private static final String TASK_ID = "test_verify_001";
    private static final int BATCH_SIZE = 100;

    private Map<String, String> fieldMapping;
    private List<MappingRule> mappingRules;
    private String pkField;

    @BeforeEach
    void setUp() {
        fieldMapping = TestDataBuilder.createDefaultFieldMapping();
        mappingRules = TestDataBuilder.createDefaultMappingRules();
        pkField = "id";
    }

    @Test
    @DisplayName("单批次校验 - 所有数据匹配")
    void verifyBatch_AllMatch_ShouldReturnAllMatch() {
        List<Map<String, Object>> sourceBatch = TestDataBuilder.createBatchSourceRecords(1L, 10);
        List<Map<String, Object>> targetBatch = TestDataBuilder.createBatchTargetRecords(1L, 10);

        IncrementalVerifyService.VerifyBatchResult result = verifyService.verifyBatch(
            TASK_ID, 0L, 10L, fieldMapping, mappingRules, pkField, sourceBatch, targetBatch);

        assertTrue(result.isSuccess());
        assertEquals(10L, result.getTotalVerified());
        assertEquals(10L, result.getMatchCount());
        assertEquals(0L, result.getDiffCount());
        assertTrue(result.getDiffs().isEmpty());
    }

    @Test
    @DisplayName("单批次校验 - 部分数据差异")
    void verifyBatch_PartialDiff_ShouldReturnDiffCount() {
        List<Map<String, Object>> sourceBatch = TestDataBuilder.createBatchSourceRecords(1L, 10);
        List<Map<String, Object>> targetBatch = TestDataBuilder.createBatchTargetRecordsWithDiff(1L, 10, 5L);

        IncrementalVerifyService.VerifyBatchResult result = verifyService.verifyBatch(
            TASK_ID, 0L, 10L, fieldMapping, mappingRules, pkField, sourceBatch, targetBatch);

        assertTrue(result.isSuccess());
        assertEquals(10L, result.getTotalVerified());
        assertEquals(9L, result.getMatchCount());
        assertEquals(1L, result.getDiffCount());
        assertEquals(1, result.getDiffs().size());
        assertEquals("5", result.getDiffs().get(0).getRecordKey());
        assertEquals(DiffType.VALUE_DIFF, result.getDiffs().get(0).getDiffType());
    }

    @Test
    @DisplayName("单批次校验 - 目标缺失记录")
    void verifyBatch_MissingInTarget_ShouldReturnMissingDiff() {
        List<Map<String, Object>> sourceBatch = TestDataBuilder.createBatchSourceRecords(1L, 10);
        List<Map<String, Object>> targetBatch = TestDataBuilder.createBatchTargetRecords(1L, 9);

        IncrementalVerifyService.VerifyBatchResult result = verifyService.verifyBatch(
            TASK_ID, 0L, 10L, fieldMapping, mappingRules, pkField, sourceBatch, targetBatch);

        assertTrue(result.isSuccess());
        assertEquals(10L, result.getTotalVerified());
        assertEquals(9L, result.getMatchCount());
        assertEquals(1L, result.getDiffCount());
        assertEquals(DiffType.MISSING_IN_TARGET, result.getDiffs().get(0).getDiffType());
    }

    @Test
    @DisplayName("单批次校验 - 源缺失记录（目标有多余）")
    void verifyBatch_MissingInSource_ShouldReturnExtraInTarget() {
        List<Map<String, Object>> sourceBatch = TestDataBuilder.createBatchSourceRecords(1L, 9);
        List<Map<String, Object>> targetBatch = TestDataBuilder.createBatchTargetRecords(1L, 10);

        IncrementalVerifyService.VerifyBatchResult result = verifyService.verifyBatch(
            TASK_ID, 0L, 10L, fieldMapping, mappingRules, pkField, sourceBatch, targetBatch);

        assertTrue(result.isSuccess());
        assertEquals(10L, result.getTotalVerified());
        assertEquals(9L, result.getMatchCount());
        assertEquals(1L, result.getDiffCount());
        assertEquals(DiffType.MISSING_IN_SOURCE, result.getDiffs().get(0).getDiffType());
    }

    @Test
    @DisplayName("多批次校验 - 批次边界处理")
    void validateBatchBoundary_ValidBoundary_ShouldReturnTrue() {
        assertTrue(verifyService.validateBatchBoundary(0L, 100L, 1000L, 100));
        assertTrue(verifyService.validateBatchBoundary(100L, 200L, 1000L, 100));
        assertTrue(verifyService.validateBatchBoundary(900L, 1000L, 1000L, 100));
    }

    @Test
    @DisplayName("多批次校验 - 无效边界检测")
    void validateBatchBoundary_InvalidBoundary_ShouldReturnFalse() {
        assertFalse(verifyService.validateBatchBoundary(-1L, 100L, 1000L, 100));
        assertFalse(verifyService.validateBatchBoundary(100L, 100L, 1000L, 100));
        assertFalse(verifyService.validateBatchBoundary(1000L, 1100L, 1000L, 100));
        assertFalse(verifyService.validateBatchBoundary(0L, 150L, 1000L, 100));
    }

    @Test
    @DisplayName("多批次校验 - 批次结果一致性验证")
    void verifyBatchResultsConsistency_AllMatch_ShouldReturnTrue() {
        List<IncrementalVerifyService.VerifyBatchResult> batchResults = new ArrayList<>();
        batchResults.add(new IncrementalVerifyService.VerifyBatchResult(
            0L, 100L, 100L, 98L, 2L, Collections.emptyList()));
        batchResults.add(new IncrementalVerifyService.VerifyBatchResult(
            100L, 200L, 100L, 95L, 5L, Collections.emptyList()));
        batchResults.add(new IncrementalVerifyService.VerifyBatchResult(
            200L, 300L, 100L, 100L, 0L, Collections.emptyList()));

        assertTrue(verifyService.verifyBatchResultsConsistency(batchResults, 300L));
    }

    @Test
    @DisplayName("多批次校验 - 批次结果不一致检测")
    void verifyBatchResultsConsistency_Mismatch_ShouldReturnFalse() {
        List<IncrementalVerifyService.VerifyBatchResult> batchResults = new ArrayList<>();
        batchResults.add(new IncrementalVerifyService.VerifyBatchResult(
            0L, 100L, 100L, 98L, 2L, Collections.emptyList()));
        batchResults.add(new IncrementalVerifyService.VerifyBatchResult(
            100L, 200L, 100L, 95L, 5L, Collections.emptyList()));

        assertFalse(verifyService.verifyBatchResultsConsistency(batchResults, 300L));
    }

    @Test
    @DisplayName("增量比对与全量比对一致性")
    void verifyIncrementalVsFull_ConsistentResults_ShouldReturnTrue() {
        long fullTotal = 500L;
        long fullMatch = 490L;
        long fullDiff = 10L;

        List<IncrementalVerifyService.VerifyBatchResult> incrementalResults = 
            generateConsistentBatchResults(fullTotal, fullMatch, fullDiff);

        assertTrue(verifyService.verifyIncrementalVsFull(
            incrementalResults, fullTotal, fullMatch, fullDiff));
    }

    @Test
    @DisplayName("增量比对与全量比对不一致检测")
    void verifyIncrementalVsFull_Inconsistent_ShouldReturnFalse() {
        long fullTotal = 500L;
        long fullMatch = 490L;
        long fullDiff = 10L;

        List<IncrementalVerifyService.VerifyBatchResult> incrementalResults = 
            generateConsistentBatchResults(500L, 480L, 20L);

        assertFalse(verifyService.verifyIncrementalVsFull(
            incrementalResults, fullTotal, fullMatch, fullDiff));
    }

    @Test
    @DisplayName("大数据量校验 - 百万级数据批次处理性能")
    void measureBatchPerformance_LargeDataset_ShouldHaveReasonableThroughput() {
        long recordsPerBatch = 10000L;
        int batchCount = 100;

        double throughput = verifyService.measureBatchPerformance(recordsPerBatch, batchCount);

        assertTrue(throughput > 0, "吞吐量应大于0");
        assertTrue(throughput > 10000, "百万级数据校验吞吐量应超过1万条/秒，实际: " + (int) throughput);
    }

    @Test
    @DisplayName("大数据量校验 - 生成大型批次结果集")
    void generateLargeBatchResults_MillionRecords_ShouldGenerateCorrectly() {
        long totalRecords = 1000000L;
        int batchSize = 1000;
        long errorRatePerBatch = 1;

        List<IncrementalVerifyService.VerifyBatchResult> results = 
            verifyService.generateLargeBatchResults(totalRecords, batchSize, errorRatePerBatch);

        int expectedBatches = (int) Math.ceil((double) totalRecords / batchSize);
        assertEquals(expectedBatches, results.size());

        long totalVerified = results.stream().mapToLong(IncrementalVerifyService.VerifyBatchResult::getTotalVerified).sum();
        assertEquals(totalRecords, totalVerified);
    }

    @Test
    @DisplayName("字段映射转换 - 大写转换")
    void verifyBatch_WithUppercaseTransformation_ShouldTransform() {
        Map<String, Object> sourceRecord = new LinkedHashMap<>();
        sourceRecord.put("id", 1L);
        sourceRecord.put("name", "john");
        sourceRecord.put("email", "john@test.com");

        Map<String, Object> targetRecord = new LinkedHashMap<>();
        targetRecord.put("user_id", 1L);
        targetRecord.put("user_name", "JOHN");
        targetRecord.put("user_email", "john@test.com");

        Map<String, String> mapping = new HashMap<>();
        mapping.put("id", "user_id");
        mapping.put("name", "user_name");
        mapping.put("email", "user_email");

        List<MappingRule> rules = new ArrayList<>();
        rules.add(TestDataBuilder.createMappingRule(1L, "name", "user_name", "uppercase", 2));

        IncrementalVerifyService.VerifyBatchResult result = verifyService.verifyBatch(
            TASK_ID, 0L, 1L, mapping, rules, pkField,
            Collections.singletonList(sourceRecord), Collections.singletonList(targetRecord));

        assertTrue(result.isSuccess());
        assertEquals(1L, result.getMatchCount());
        assertEquals(0L, result.getDiffCount());
    }

    @Test
    @DisplayName("字段级差异检测 - 多字段差异")
    void verifyBatch_MultipleFieldDiffs_ShouldReturnAllDiffFields() {
        Map<String, Object> sourceRecord = new LinkedHashMap<>();
        sourceRecord.put("id", 1L);
        sourceRecord.put("name", "OriginalName");
        sourceRecord.put("email", "original@test.com");

        Map<String, Object> targetRecord = new LinkedHashMap<>();
        targetRecord.put("user_id", 1L);
        targetRecord.put("user_name", "ModifiedName");
        targetRecord.put("user_email", "modified@test.com");

        IncrementalVerifyService.VerifyBatchResult result = verifyService.verifyBatch(
            TASK_ID, 0L, 1L, fieldMapping, mappingRules, pkField,
            Collections.singletonList(sourceRecord), Collections.singletonList(targetRecord));

        assertTrue(result.isSuccess());
        assertEquals(1L, result.getDiffCount());
        assertEquals(2, result.getDiffs().get(0).getDiffFields().size());
        assertTrue(result.getDiffs().get(0).getDiffFields().contains("user_name"));
        assertTrue(result.getDiffs().get(0).getDiffFields().contains("user_email"));
    }

    @Test
    @DisplayName("空映射规则 - 使用原始字段名")
    void verifyBatch_EmptyMapping_ShouldUseOriginalFields() {
        Map<String, String> emptyMapping = new HashMap<>();
        List<MappingRule> emptyRules = new ArrayList<>();

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", 1L);
        record.put("name", "Test");
        record.put("email", "test@test.com");

        IncrementalVerifyService.VerifyBatchResult result = verifyService.verifyBatch(
            TASK_ID, 0L, 1L, emptyMapping, emptyRules, pkField,
            Collections.singletonList(record), Collections.singletonList(record));

        assertTrue(result.isSuccess());
        assertEquals(1L, result.getMatchCount());
    }

    @Test
    @DisplayName("null值比较 - null与null相等")
    void verifyBatch_NullValues_ShouldBeEqual() {
        Map<String, Object> sourceRecord = new LinkedHashMap<>();
        sourceRecord.put("id", 1L);
        sourceRecord.put("name", null);

        Map<String, Object> targetRecord = new LinkedHashMap<>();
        targetRecord.put("user_id", 1L);
        targetRecord.put("user_name", null);

        Map<String, String> mapping = new HashMap<>();
        mapping.put("id", "user_id");
        mapping.put("name", "user_name");

        IncrementalVerifyService.VerifyBatchResult result = verifyService.verifyBatch(
            TASK_ID, 0L, 1L, mapping, mappingRules, pkField,
            Collections.singletonList(sourceRecord), Collections.singletonList(targetRecord));

        assertTrue(result.isSuccess());
        assertEquals(1L, result.getMatchCount());
    }

    private List<IncrementalVerifyService.VerifyBatchResult> generateConsistentBatchResults(
            long total, long matches, long diffs) {
        List<IncrementalVerifyService.VerifyBatchResult> results = new ArrayList<>();
        int batchSize = 100;
        long remainingMatches = matches;
        long remainingDiffs = diffs;
        
        for (long i = 0; i < total; i += batchSize) {
            long batchEnd = Math.min(i + batchSize, total);
            long batchSizeActual = batchEnd - i;
            
            long batchDiffs = Math.min(batchSizeActual, remainingDiffs);
            long batchMatches = batchSizeActual - batchDiffs;
            
            remainingDiffs -= batchDiffs;
            remainingMatches -= batchMatches;

            List<IncrementalVerifyService.DiffInfo> batchDiffsList = new ArrayList<>();
            for (int j = 0; j < batchDiffs; j++) {
                batchDiffsList.add(new IncrementalVerifyService.DiffInfo(
                    "key_" + (i + j), DiffType.VALUE_DIFF, 
                    Arrays.asList("name"), null, null));
            }

            results.add(new IncrementalVerifyService.VerifyBatchResult(
                i, batchEnd, batchSizeActual, batchMatches, batchDiffs, batchDiffsList));
        }
        return results;
    }
}
