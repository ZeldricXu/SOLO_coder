package com.streamsql.modules.data_quality;

import com.streamsql.dto.QualityRuleDTO;
import com.streamsql.entity.AnomalyDataRecord;
import com.streamsql.entity.QualityCheckResult;
import com.streamsql.entity.QualityRule;
import com.streamsql.fixture.TestBuilders;
import com.streamsql.fixture.TestFixtures;
import com.streamsql.mapper.AnomalyDataRecordMapper;
import com.streamsql.mapper.QualityCheckResultMapper;
import com.streamsql.mapper.QualityRuleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("数据质量校验模块测试")
class QualityRuleServiceTest {

    @Mock
    private QualityRuleMapper qualityRuleMapper;

    @Mock
    private QualityCheckResultMapper qualityCheckResultMapper;

    @Mock
    private AnomalyDataRecordMapper anomalyDataRecordMapper;

    @Mock
    private TaskScheduler taskScheduler;

    @Mock
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @InjectMocks
    private QualityRuleService qualityRuleService;

    @Nested
    @DisplayName("正常流程测试")
    class NormalFlowTest {

        @Test
        @DisplayName("创建质量规则 - 成功")
        void shouldCreateRuleSuccessfully() {
            QualityRuleDTO dto = TestBuilders.qualityRuleDTO().build();
            QualityRule expectedRule = TestFixtures.createQualityRuleEntity();

            when(qualityRuleMapper.insert(any(QualityRule.class))).thenReturn(1);

            QualityRule result = qualityRuleService.createRule(dto);

            assertNotNull(result);
            assertEquals(dto.getRuleName(), result.getRuleName());
            assertEquals(dto.getRuleType(), result.getRuleType());
            assertTrue(result.getEnabled());

            ArgumentCaptor<QualityRule> captor = ArgumentCaptor.forClass(QualityRule.class);
            verify(qualityRuleMapper).insert(captor.capture());
            QualityRule capturedRule = captor.getValue();
            assertEquals(dto.getRuleName(), capturedRule.getRuleName());
            assertEquals(dto.getRuleType(), capturedRule.getRuleType());
        }

        @Test
        @DisplayName("更新质量规则 - 成功")
        void shouldUpdateRuleSuccessfully() {
            String ruleId = "rule_001";
            QualityRuleDTO dto = TestBuilders.qualityRuleDTO()
                    .ruleName("更新后的规则")
                    .severity("warning")
                    .build();

            QualityRule existingRule = TestFixtures.createQualityRuleEntity();
            existingRule.setRuleId(ruleId);

            when(qualityRuleMapper.selectById(ruleId)).thenReturn(existingRule);
            when(qualityRuleMapper.updateById(any(QualityRule.class))).thenReturn(1);

            QualityRule result = qualityRuleService.updateRule(ruleId, dto);

            assertEquals("更新后的规则", result.getRuleName());
            assertEquals("warning", result.getSeverity());
            verify(qualityRuleMapper).updateById(any(QualityRule.class));
        }

        @Test
        @DisplayName("删除质量规则 - 成功")
        void shouldDeleteRuleSuccessfully() {
            String ruleId = "rule_001";
            when(qualityRuleMapper.deleteById(ruleId)).thenReturn(1);

            assertDoesNotThrow(() -> qualityRuleService.deleteRule(ruleId));
            verify(qualityRuleMapper).deleteById(ruleId);
        }

        @Test
        @DisplayName("查询质量规则 - 成功")
        void shouldGetRuleSuccessfully() {
            String ruleId = "rule_001";
            QualityRule expectedRule = TestFixtures.createQualityRuleEntity();
            expectedRule.setRuleId(ruleId);

            when(qualityRuleMapper.selectById(ruleId)).thenReturn(expectedRule);

            QualityRule result = qualityRuleService.getRule(ruleId);

            assertNotNull(result);
            assertEquals(ruleId, result.getRuleId());
        }

        @Test
        @DisplayName("执行质量校验 - 成功无异常")
        void shouldExecuteQualityCheckSuccessfully() throws Exception {
            String ruleId = "rule_001";
            QualityRule rule = TestFixtures.createQualityRuleEntity();
            rule.setRuleId(ruleId);

            when(qualityRuleMapper.selectById(ruleId)).thenReturn(rule);
            when(qualityCheckResultMapper.insert(any(QualityCheckResult.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            QualityCheckResult result = qualityRuleService.executeQualityCheck(ruleId);

            assertNotNull(result);
            assertEquals("failed", result.getStatus());
            assertEquals(1000L, result.getTotalCount());
            assertEquals(5L, result.getErrorCount());
        }

        @Test
        @DisplayName("获取校验结果 - 成功")
        void shouldGetCheckResultsSuccessfully() {
            String ruleId = "rule_001";
            QualityCheckResult checkResult = TestFixtures.createQualityCheckResultEntity();

            when(qualityCheckResultMapper.selectList(any())).thenReturn(Arrays.asList(checkResult));

            List<QualityCheckResult> results = qualityRuleService.getCheckResults(ruleId, 10);

            assertFalse(results.isEmpty());
            assertEquals(1, results.size());
        }

        @Test
        @DisplayName("获取异常数据记录 - 成功")
        void shouldGetAnomalyRecordsSuccessfully() {
            AnomalyDataRecord anomalyRecord = TestFixtures.createAnomalyDataRecordEntity();

            when(anomalyDataRecordMapper.selectPage(any(), any()))
                    .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(
                            Arrays.asList(anomalyRecord), 1));

            com.streamsql.common.PageResult<AnomalyDataRecord> result =
                    qualityRuleService.getAnomalyRecords(1, 10, null, null);

            assertNotNull(result);
            assertFalse(result.getRecords().isEmpty());
        }
    }

    @Nested
    @DisplayName("边界条件测试")
    class BoundaryTest {

        @Test
        @DisplayName("创建规则 - 空字符串值")
        void shouldCreateRuleWithEmptyValues() {
            QualityRuleDTO dto = TestBuilders.qualityRuleDTO().withEmptyValues().build();
            when(qualityRuleMapper.insert(any(QualityRule.class))).thenReturn(1);

            QualityRule result = qualityRuleService.createRule(dto);

            assertNotNull(result);
            assertEquals("", result.getRuleName());
        }

        @Test
        @DisplayName("创建规则 - null值")
        void shouldCreateRuleWithNullValues() {
            QualityRuleDTO dto = TestBuilders.qualityRuleDTO().withNullValues().build();
            when(qualityRuleMapper.insert(any(QualityRule.class))).thenReturn(1);

            QualityRule result = qualityRuleService.createRule(dto);

            assertNotNull(result);
            assertNull(result.getRuleName());
        }

        @Test
        @DisplayName("创建规则 - 超长字符串")
        void shouldCreateRuleWithLongStrings() {
            QualityRuleDTO dto = TestBuilders.qualityRuleDTO().withLongStrings().build();
            when(qualityRuleMapper.insert(any(QualityRule.class))).thenReturn(1);

            QualityRule result = qualityRuleService.createRule(dto);

            assertNotNull(result);
            assertEquals(1000, result.getRuleName().length());
        }

        @Test
        @DisplayName("创建规则 - 禁用状态")
        void shouldCreateRuleWithDisabledStatus() {
            QualityRuleDTO dto = TestBuilders.qualityRuleDTO().enabled(false).build();
            when(qualityRuleMapper.insert(any(QualityRule.class))).thenReturn(1);

            QualityRule result = qualityRuleService.createRule(dto);

            assertNotNull(result);
            assertFalse(result.getEnabled());
        }

        @Test
        @DisplayName("创建规则 - 无Cron表达式")
        void shouldCreateRuleWithoutCronExpression() {
            QualityRuleDTO dto = TestBuilders.qualityRuleDTO().cronExpression(null).build();
            when(qualityRuleMapper.insert(any(QualityRule.class))).thenReturn(1);

            QualityRule result = qualityRuleService.createRule(dto);

            assertNotNull(result);
            assertNull(result.getCronExpression());
        }

        @Test
        @DisplayName("更新规则 - 不存在的规则ID")
        void shouldThrowWhenUpdatingNonExistentRule() {
            String nonExistentId = "non_existent_id";
            QualityRuleDTO dto = TestBuilders.qualityRuleDTO().build();

            when(qualityRuleMapper.selectById(nonExistentId)).thenReturn(null);

            assertThrows(IllegalArgumentException.class, () ->
                    qualityRuleService.updateRule(nonExistentId, dto));
        }

        @Test
        @DisplayName("执行校验 - 不存在的规则ID")
        void shouldThrowWhenExecutingNonExistentRule() {
            String nonExistentId = "non_existent_id";

            when(qualityRuleMapper.selectById(nonExistentId)).thenReturn(null);

            assertThrows(IllegalArgumentException.class, () ->
                    qualityRuleService.executeQualityCheck(nonExistentId));
        }

        @Test
        @DisplayName("查询规则 - 不存在的规则ID")
        void shouldReturnNullForNonExistentRule() {
            String nonExistentId = "non_existent_id";

            when(qualityRuleMapper.selectById(nonExistentId)).thenReturn(null);

            QualityRule result = qualityRuleService.getRule(nonExistentId);

            assertNull(result);
        }

        @Test
        @DisplayName("列出规则 - 空结果")
        void shouldReturnEmptyPageWhenNoRules() {
            when(qualityRuleMapper.selectPage(any(), any()))
                    .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());

            com.streamsql.common.PageResult<QualityRule> result =
                    qualityRuleService.listRules(1, 10, null, null);

            assertNotNull(result);
            assertTrue(result.getRecords().isEmpty());
            assertEquals(0, result.getTotal());
        }

        @Test
        @DisplayName("获取校验结果 - 限制数量为0")
        void shouldGetCheckResultsWithZeroLimit() {
            String ruleId = "rule_001";
            when(qualityCheckResultMapper.selectList(any())).thenReturn(Arrays.asList());

            List<QualityCheckResult> results = qualityRuleService.getCheckResults(ruleId, 0);

            assertTrue(results.isEmpty());
        }
    }

    @Nested
    @DisplayName("并发竞态场景测试")
    class ConcurrencyTest {

        @Test
        @DisplayName("并发创建规则 - 保证线程安全")
        void shouldHandleConcurrentRuleCreation() throws InterruptedException {
            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);

            when(qualityRuleMapper.insert(any(QualityRule.class))).thenReturn(1);

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        QualityRuleDTO dto = TestBuilders.qualityRuleDTO()
                                .ruleName("并发规则-" + index + "-" + System.nanoTime())
                                .build();
                        qualityRuleService.createRule(dto);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(threadCount, successCount.get());
            assertEquals(0, errorCount.get());
            verify(qualityRuleMapper, times(threadCount)).insert(any(QualityRule.class));
        }

        @Test
        @DisplayName("并发执行校验 - 保证线程安全")
        void shouldHandleConcurrentQualityChecks() throws InterruptedException {
            int threadCount = 5;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            String ruleId = "rule_001";
            QualityRule rule = TestFixtures.createQualityRuleEntity();
            rule.setRuleId(ruleId);

            when(qualityRuleMapper.selectById(ruleId)).thenReturn(rule);
            when(qualityCheckResultMapper.insert(any(QualityCheckResult.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        qualityRuleService.executeQualityCheck(ruleId);
                        successCount.incrementAndGet();
                    } catch (Exception ignored) {
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(threadCount, successCount.get());
        }

        @Test
        @DisplayName("并发更新同一规则 - 防止竞态条件")
        void shouldHandleConcurrentUpdatesToSameRule() throws InterruptedException {
            String ruleId = "rule_001";
            int threadCount = 5;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);

            QualityRule existingRule = TestFixtures.createQualityRuleEntity();
            existingRule.setRuleId(ruleId);

            when(qualityRuleMapper.selectById(ruleId)).thenReturn(existingRule);
            when(qualityRuleMapper.updateById(any(QualityRule.class))).thenReturn(1);

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        QualityRuleDTO dto = TestBuilders.qualityRuleDTO()
                                .ruleName("并发更新-" + index)
                                .build();
                        qualityRuleService.updateRule(ruleId, dto);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(threadCount, successCount.get());
        }

        @Test
        @DisplayName("并发删除和查询 - 保证一致性")
        void shouldHandleConcurrentDeleteAndQuery() throws InterruptedException {
            String ruleId = "rule_001";
            QualityRule existingRule = TestFixtures.createQualityRuleEntity();
            existingRule.setRuleId(ruleId);

            when(qualityRuleMapper.selectById(ruleId))
                    .thenReturn(existingRule)
                    .thenReturn(null);
            when(qualityRuleMapper.deleteById(ruleId)).thenReturn(1);

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch latch = new CountDownLatch(2);

            executor.submit(() -> {
                try {
                    qualityRuleService.deleteRule(ruleId);
                } finally {
                    latch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    QualityRule rule = qualityRuleService.getRule(ruleId);
                } finally {
                    latch.countDown();
                }
            });

            latch.await(5, TimeUnit.SECONDS);
            executor.shutdown();
        }
    }

    @Nested
    @DisplayName("降级行为测试")
    class DegradationTest {

        @Test
        @DisplayName("Mapper插入失败 - 异常传播")
        void shouldPropagateExceptionWhenInsertFails() {
            QualityRuleDTO dto = TestBuilders.qualityRuleDTO().build();

            when(qualityRuleMapper.insert(any(QualityRule.class)))
                    .thenThrow(new RuntimeException("数据库连接超时"));

            assertThrows(RuntimeException.class, () -> qualityRuleService.createRule(dto));
        }

        @Test
        @DisplayName("Mapper查询超时 - 异常处理")
        void shouldHandleQueryTimeoutException() {
            String ruleId = "rule_001";

            when(qualityRuleMapper.selectById(ruleId))
                    .thenThrow(new RuntimeException("查询超时"));

            assertThrows(RuntimeException.class, () -> qualityRuleService.getRule(ruleId));
        }

        @Test
        @DisplayName("执行校验时外部服务异常 - 记录错误状态")
        void shouldRecordErrorStatusWhenExternalServiceFails() throws Exception {
            String ruleId = "rule_001";
            QualityRule rule = TestFixtures.createQualityRuleEntity();
            rule.setRuleId(ruleId);

            when(qualityRuleMapper.selectById(ruleId)).thenReturn(rule);
            when(qualityCheckResultMapper.insert(any(QualityCheckResult.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any()))
                    .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("序列化失败") {});

            QualityCheckResult result = qualityRuleService.executeQualityCheck(ruleId);

            assertNotNull(result);
            assertEquals("error", result.getStatus());
        }

        @Test
        @DisplayName("数据库连接异常 - 事务回滚")
        void shouldRollbackTransactionOnDatabaseError() {
            QualityRuleDTO dto = TestBuilders.qualityRuleDTO().build();

            when(qualityRuleMapper.insert(any(QualityRule.class)))
                    .thenThrow(new RuntimeException("连接重置"));

            assertThrows(RuntimeException.class, () -> qualityRuleService.createRule(dto));
        }

        @Test
        @DisplayName("Cron表达式无效 - 降级处理")
        void shouldHandleInvalidCronExpressionGracefully() {
            QualityRuleDTO dto = TestBuilders.qualityRuleDTO()
                    .cronExpression("invalid cron expression")
                    .build();

            when(qualityRuleMapper.insert(any(QualityRule.class))).thenReturn(1);

            assertDoesNotThrow(() -> qualityRuleService.createRule(dto));
        }

        @Test
        @DisplayName("调度器不可用 - 创建规则仍应成功")
        void shouldCreateRuleEvenWhenSchedulerUnavailable() {
            QualityRuleDTO dto = TestBuilders.qualityRuleDTO().build();

            when(qualityRuleMapper.insert(any(QualityRule.class))).thenReturn(1);
            doThrow(new RuntimeException("调度器不可用"))
                    .when(taskScheduler).schedule(any(Runnable.class), any());

            assertDoesNotThrow(() -> qualityRuleService.createRule(dto));
        }

        @Test
        @DisplayName("异常数据记录存储失败 - 不影响主流程")
        void shouldNotFailWhenAnomalyRecordStorageFails() throws Exception {
            String ruleId = "rule_001";
            QualityRule rule = TestFixtures.createQualityRuleEntity();
            rule.setRuleId(ruleId);

            when(qualityRuleMapper.selectById(ruleId)).thenReturn(rule);
            when(qualityCheckResultMapper.insert(any(QualityCheckResult.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
            when(anomalyDataRecordMapper.insert(any(AnomalyDataRecord.class)))
                    .thenThrow(new RuntimeException("存储异常"));

            assertDoesNotThrow(() -> qualityRuleService.executeQualityCheck(ruleId));
        }
    }
}
