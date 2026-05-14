package com.contractmgmt.service;

import com.contractmgmt.builder.TestDataBuilder;
import com.contractmgmt.entity.Contract;
import com.contractmgmt.entity.ExecutionRecord;
import com.contractmgmt.repository.ContractRepository;
import com.contractmgmt.repository.ExecutionRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("执行定期检查单元测试")
class ExecutionCheckServiceTest {

    @Mock
    private ContractRepository contractRepository;

    @Mock
    private ExecutionRecordRepository executionRecordRepository;

    @InjectMocks
    private ExecutionCheckService executionCheckService;

    @Nested
    @DisplayName("执行检查测试")
    class ExecutionCheckTests {

        @Test
        @DisplayName("没有已生效合同时应返回空结果")
        void checkExecutions_WithNoActiveContracts_ShouldReturnEmpty() {
            when(contractRepository.findByContractStatusIn(anyList()))
                    .thenReturn(Collections.emptyList());

            List<ExecutionCheckService.ExecutionCheckResult> results =
                    executionCheckService.checkExecutions();

            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("已完成的合同应被跳过")
        void checkExecutions_WithCompletedContract_ShouldBeSkipped() {
            Contract completed = TestDataBuilder.buildCompletedContract();

            when(contractRepository.findByContractStatusIn(anyList()))
                    .thenReturn(Arrays.asList(completed));

            List<ExecutionCheckService.ExecutionCheckResult> results =
                    executionCheckService.checkExecutions();

            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("正常执行的合同应返回正常状态")
        void checkExecutions_WithNormalContract_ShouldBeNormal() {
            Contract normal = TestDataBuilder.buildActiveContract();
            normal.setContractStart(LocalDate.now().minusDays(30));
            normal.setContractEnd(LocalDate.now().plusDays(150));
            normal.setExecutionProgress(20);
            ExecutionRecord recentRecord = TestDataBuilder.buildPartialExecutionRecord(normal.getContractId());
            recentRecord.setExecutionTime(LocalDateTime.now().minusDays(3));

            when(contractRepository.findByContractStatusIn(anyList()))
                    .thenReturn(Arrays.asList(normal));
            when(executionRecordRepository.findByContractIdOrderByExecutionTimeDesc(normal.getContractId()))
                    .thenReturn(Arrays.asList(recentRecord));

            List<ExecutionCheckService.ExecutionCheckResult> results =
                    executionCheckService.checkExecutions();

            assertEquals(1, results.size());
            assertEquals(ExecutionCheckService.ExecutionStatus.NORMAL, results.get(0).status);
        }

        @Test
        @DisplayName("进度达到100%但状态未更新应自动完成")
        void checkExecutions_WithProgress100ButNotCompleted_ShouldAutoComplete() {
            Contract contract = TestDataBuilder.buildApprovedContract();
            contract.setExecutionProgress(100);
            contract.setExecutionStatus("in_progress");
            ExecutionRecord fullRecord = TestDataBuilder.buildFullExecutionRecord(contract.getContractId());
            fullRecord.setExecutionTime(LocalDateTime.now().minusDays(5));

            when(contractRepository.findByContractStatusIn(anyList()))
                    .thenReturn(Arrays.asList(contract));
            when(executionRecordRepository.findByContractIdOrderByExecutionTimeDesc(contract.getContractId()))
                    .thenReturn(Arrays.asList(fullRecord));
            when(contractRepository.save(any(Contract.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            List<ExecutionCheckService.ExecutionCheckResult> results =
                    executionCheckService.checkExecutions();

            assertEquals(1, results.size());
            assertEquals(ExecutionCheckService.ExecutionStatus.AUTO_COMPLETE, results.get(0).status);

            verify(contractRepository, times(1)).save(argThat(c ->
                    "completed".equals(c.getExecutionStatus())));
        }

        @Test
        @DisplayName("执行进度落后应返回延迟状态")
        void checkExecutions_WithProgressLag_ShouldBeDelayed() {
            Contract delayed = TestDataBuilder.buildActiveContract();
            delayed.setContractStart(LocalDate.now().minusDays(100));
            delayed.setContractEnd(LocalDate.now().plusDays(80));
            delayed.setExecutionProgress(10);
            ExecutionRecord oldRecord = TestDataBuilder.buildExecutionRecord(delayed.getContractId(), 10);
            oldRecord.setExecutionTime(LocalDateTime.now().minusDays(5));

            when(contractRepository.findByContractStatusIn(anyList()))
                    .thenReturn(Arrays.asList(delayed));
            when(executionRecordRepository.findByContractIdOrderByExecutionTimeDesc(delayed.getContractId()))
                    .thenReturn(Arrays.asList(oldRecord));

            List<ExecutionCheckService.ExecutionCheckResult> results =
                    executionCheckService.checkExecutions();

            assertEquals(1, results.size());
            assertEquals(ExecutionCheckService.ExecutionStatus.DELAYED, results.get(0).status);
            assertTrue(results.get(0).issueDescription.contains("落后"));
        }

        @Test
        @DisplayName("长时间未更新应返回停滞状态")
        void checkExecutions_WithLongTimeNoUpdate_ShouldBeStalled() {
            Contract stalled = TestDataBuilder.buildActiveContract();
            stalled.setContractStart(LocalDate.now().minusDays(30));
            stalled.setContractEnd(LocalDate.now().plusDays(150));
            stalled.setExecutionProgress(30);
            ExecutionRecord oldRecord = TestDataBuilder.buildExecutionRecord(stalled.getContractId(), 30);
            oldRecord.setExecutionTime(LocalDateTime.now().minusDays(10));

            when(contractRepository.findByContractStatusIn(anyList()))
                    .thenReturn(Arrays.asList(stalled));
            when(executionRecordRepository.findByContractIdOrderByExecutionTimeDesc(stalled.getContractId()))
                    .thenReturn(Arrays.asList(oldRecord));

            List<ExecutionCheckService.ExecutionCheckResult> results =
                    executionCheckService.checkExecutions();

            assertEquals(1, results.size());
            assertEquals(ExecutionCheckService.ExecutionStatus.STALLED, results.get(0).status);
            assertTrue(results.get(0).issueDescription.contains("未更新"));
        }

        @Test
        @DisplayName("从未执行的合同应被正确检测")
        void checkExecutions_WithNoExecutionRecords_ShouldCheckCorrectly() {
            Contract contract = TestDataBuilder.buildApprovedContract();
            contract.setContractStart(LocalDate.now().minusDays(30));
            contract.setContractEnd(LocalDate.now().plusDays(150));
            contract.setExecutionProgress(0);
            contract.setExecutionStatus("in_progress");
            contract.setEffectiveTime(LocalDateTime.now().minusDays(10));

            when(contractRepository.findByContractStatusIn(anyList()))
                    .thenReturn(Arrays.asList(contract));
            when(executionRecordRepository.findByContractIdOrderByExecutionTimeDesc(contract.getContractId()))
                    .thenReturn(Collections.emptyList());

            List<ExecutionCheckService.ExecutionCheckResult> results =
                    executionCheckService.checkExecutions();

            assertEquals(1, results.size());
            assertEquals(ExecutionCheckService.ExecutionStatus.STALLED, results.get(0).status);
            assertTrue(results.get(0).daysSinceLastUpdate >= 7);
        }
    }

    @Nested
    @DisplayName("执行进度准确性测试")
    class ProgressAccuracyTests {

        @Test
        @DisplayName("null进度应返回false")
        void checkProgressAccuracy_WithNullProgress_ShouldReturnFalse() {
            String contractId = TestDataBuilder.TEST_CONTRACT_ID;

            boolean result = executionCheckService.checkProgressAccuracy(null, contractId);

            assertFalse(result);
        }

        @Test
        @DisplayName("负进度应返回false")
        void checkProgressAccuracy_WithNegativeProgress_ShouldReturnFalse() {
            String contractId = TestDataBuilder.TEST_CONTRACT_ID;

            boolean result = executionCheckService.checkProgressAccuracy(-1, contractId);

            assertFalse(result);
        }

        @Test
        @DisplayName("超过100的进度应返回false")
        void checkProgressAccuracy_WithOver100Progress_ShouldReturnFalse() {
            String contractId = TestDataBuilder.TEST_CONTRACT_ID;

            boolean result = executionCheckService.checkProgressAccuracy(101, contractId);

            assertFalse(result);
        }

        @Test
        @DisplayName("无历史记录时有效进度应返回true")
        void checkProgressAccuracy_WithNoHistoryAndValidProgress_ShouldReturnTrue() {
            String contractId = TestDataBuilder.TEST_CONTRACT_ID;

            when(executionRecordRepository.findByContractIdOrderByExecutionTimeDesc(contractId))
                    .thenReturn(Collections.emptyList());

            boolean result = executionCheckService.checkProgressAccuracy(50, contractId);

            assertTrue(result);
        }

        @Test
        @DisplayName("新进度大于历史进度应返回true")
        void checkProgressAccuracy_WithProgressHigherThanHistory_ShouldReturnTrue() {
            String contractId = TestDataBuilder.TEST_CONTRACT_ID;
            ExecutionRecord record = TestDataBuilder.buildExecutionRecord(contractId, 30);

            when(executionRecordRepository.findByContractIdOrderByExecutionTimeDesc(contractId))
                    .thenReturn(Arrays.asList(record));
            when(executionRecordRepository.findMaxProgressByContractId(contractId)).thenReturn(30);

            boolean result = executionCheckService.checkProgressAccuracy(50, contractId);

            assertTrue(result);
        }

        @Test
        @DisplayName("新进度小于历史进度应返回false")
        void checkProgressAccuracy_WithProgressLowerThanHistory_ShouldReturnFalse() {
            String contractId = TestDataBuilder.TEST_CONTRACT_ID;
            ExecutionRecord record = TestDataBuilder.buildExecutionRecord(contractId, 70);

            when(executionRecordRepository.findByContractIdOrderByExecutionTimeDesc(contractId))
                    .thenReturn(Arrays.asList(record));
            when(executionRecordRepository.findMaxProgressByContractId(contractId)).thenReturn(70);

            boolean result = executionCheckService.checkProgressAccuracy(50, contractId);

            assertFalse(result);
        }
    }

    @Nested
    @DisplayName("执行遗漏检测测试")
    class MissingExecutionTests {

        @Test
        @DisplayName("从未执行的合同应被检测到")
        void findMissingExecutions_WithNeverExecuted_ShouldBeDetected() {
            Contract neverExecuted = TestDataBuilder.buildApprovedContract();
            neverExecuted.setEffectiveTime(LocalDateTime.now().minusDays(15));
            neverExecuted.setExecutionProgress(0);
            neverExecuted.setExecutionStatus("in_progress");

            when(contractRepository.findByContractStatusIn(anyList()))
                    .thenReturn(Arrays.asList(neverExecuted));
            when(executionRecordRepository.findByContractIdOrderByExecutionTimeDesc(neverExecuted.getContractId()))
                    .thenReturn(Collections.emptyList());

            List<ExecutionCheckService.ExecutionMissingItem> missing =
                    executionCheckService.findMissingExecutions();

            assertEquals(1, missing.size());
            assertEquals("从未执行", missing.get(0).issueType);
            assertEquals(neverExecuted.getContractId(), missing.get(0).contractId);
        }

        @Test
        @DisplayName("长时间未更新的合同应被检测到")
        void findMissingExecutions_WithLongTimeNoUpdate_ShouldBeDetected() {
            Contract stalled = TestDataBuilder.buildActiveContract();
            stalled.setExecutionProgress(30);
            stalled.setExecutionStatus("in_progress");
            ExecutionRecord oldRecord = TestDataBuilder.buildExecutionRecord(stalled.getContractId(), 30);
            oldRecord.setExecutionTime(LocalDateTime.now().minusDays(15));

            when(contractRepository.findByContractStatusIn(anyList()))
                    .thenReturn(Arrays.asList(stalled));
            when(executionRecordRepository.findByContractIdOrderByExecutionTimeDesc(stalled.getContractId()))
                    .thenReturn(Arrays.asList(oldRecord));

            List<ExecutionCheckService.ExecutionMissingItem> missing =
                    executionCheckService.findMissingExecutions();

            assertEquals(1, missing.size());
            assertEquals("执行记录缺失", missing.get(0).issueType);
            assertTrue(missing.get(0).daysSinceLastExecution > 7);
        }

        @Test
        @DisplayName("正常更新的合同不应被检测为遗漏")
        void findMissingExecutions_WithNormalUpdate_ShouldNotBeDetected() {
            Contract normal = TestDataBuilder.buildActiveContract();
            ExecutionRecord recentRecord = TestDataBuilder.buildExecutionRecord(normal.getContractId(), 50);
            recentRecord.setExecutionTime(LocalDateTime.now().minusDays(3));

            when(contractRepository.findByContractStatusIn(anyList()))
                    .thenReturn(Arrays.asList(normal));
            when(executionRecordRepository.findByContractIdOrderByExecutionTimeDesc(normal.getContractId()))
                    .thenReturn(Arrays.asList(recentRecord));

            List<ExecutionCheckService.ExecutionMissingItem> missing =
                    executionCheckService.findMissingExecutions();

            assertTrue(missing.isEmpty());
        }

        @Test
        @DisplayName("已完成的合同不应被检测")
        void findMissingExecutions_WithCompletedContract_ShouldNotBeDetected() {
            Contract completed = TestDataBuilder.buildCompletedContract();

            when(contractRepository.findByContractStatusIn(anyList()))
                    .thenReturn(Arrays.asList(completed));

            List<ExecutionCheckService.ExecutionMissingItem> missing =
                    executionCheckService.findMissingExecutions();

            assertTrue(missing.isEmpty());
        }
    }
}
