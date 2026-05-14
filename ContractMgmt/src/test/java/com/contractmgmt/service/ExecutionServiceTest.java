package com.contractmgmt.service;

import com.contractmgmt.builder.TestDataBuilder;
import com.contractmgmt.dto.ExecutionRequest;
import com.contractmgmt.entity.Contract;
import com.contractmgmt.entity.ExecutionRecord;
import com.contractmgmt.exception.ContractException;
import com.contractmgmt.repository.ContractRepository;
import com.contractmgmt.repository.ExecutionRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("执行模块单元测试")
class ExecutionServiceTest {

    @Mock
    private ExecutionRecordRepository executionRecordRepository;

    @Mock
    private ContractRepository contractRepository;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private ExecutionService executionService;

    @Test
    @DisplayName("初始化执行追踪不应抛出异常")
    void initializeExecutionTracking_ShouldNotThrow() {
        String contractId = TestDataBuilder.TEST_CONTRACT_ID;

        assertDoesNotThrow(() -> executionService.initializeExecutionTracking(contractId));
    }

    @Nested
    @DisplayName("执行记录测试")
    class ExecutionRecordTests {

        @Test
        @DisplayName("已生效合同应能成功记录执行")
        void recordExecution_WithApprovedContract_ShouldSucceed() {
            Contract approved = TestDataBuilder.buildApprovedContract();
            ExecutionRequest request = TestDataBuilder.buildPartialExecutionRequest(approved.getContractId());

            when(contractRepository.findByContractId(approved.getContractId()))
                    .thenReturn(Optional.of(approved));
            when(executionRecordRepository.save(any(ExecutionRecord.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(contractRepository.save(any(Contract.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            Map<String, Object> result = executionService.recordExecution(request);

            assertNotNull(result);
            assertTrue(result.containsKey("execution_id"));
            assertEquals(50, result.get("progress"));
            assertEquals("in_progress", result.get("execution_status"));

            verify(historyService, times(1)).recordHistory(
                    eq(approved.getContractId()), eq("execution"), eq("record"),
                    anyString(), anyString(), eq("0%"), eq("50%"));
        }

        @Test
        @DisplayName("执行进度100%应设置为已完成状态")
        void recordExecution_With100Progress_ShouldSetCompletedStatus() {
            Contract approved = TestDataBuilder.buildApprovedContract();
            approved.setExecutionProgress(50);
            approved.setExecutionStatus("in_progress");
            ExecutionRequest request = TestDataBuilder.buildFullExecutionRequest(approved.getContractId());

            when(contractRepository.findByContractId(approved.getContractId()))
                    .thenReturn(Optional.of(approved));
            when(executionRecordRepository.save(any(ExecutionRecord.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(contractRepository.save(any(Contract.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            Map<String, Object> result = executionService.recordExecution(request);

            assertEquals("completed", result.get("execution_status"));
            assertEquals(100, result.get("progress"));
        }

        @Test
        @DisplayName("执行进度超过100应抛出异常")
        void recordExecution_WithProgressOver100_ShouldThrowException() {
            Contract approved = TestDataBuilder.buildApprovedContract();
            ExecutionRequest request = TestDataBuilder.buildInvalidProgressRequest(approved.getContractId());

            when(contractRepository.findByContractId(approved.getContractId()))
                    .thenReturn(Optional.of(approved));

            ContractException exception = assertThrows(ContractException.class,
                    () -> executionService.recordExecution(request));

            assertEquals(400, exception.getCode());
            assertTrue(exception.getMessage().contains("进度"));
            verify(executionRecordRepository, never()).save(any());
        }

        @Test
        @DisplayName("执行进度为负数应抛出异常")
        void recordExecution_WithNegativeProgress_ShouldThrowException() {
            Contract approved = TestDataBuilder.buildApprovedContract();
            ExecutionRequest request = TestDataBuilder.buildExecutionRequest(approved.getContractId(), -1);

            when(contractRepository.findByContractId(approved.getContractId()))
                    .thenReturn(Optional.of(approved));

            ContractException exception = assertThrows(ContractException.class,
                    () -> executionService.recordExecution(request));

            assertEquals(400, exception.getCode());
        }

        @Test
        @DisplayName("不存在的合同应抛出异常")
        void recordExecution_WithNonExistingContract_ShouldThrowException() {
            String nonExistingId = "non_existing_001";
            ExecutionRequest request = TestDataBuilder.buildExecutionRequest(nonExistingId, 50);

            when(contractRepository.findByContractId(nonExistingId)).thenReturn(Optional.empty());

            ContractException exception = assertThrows(ContractException.class,
                    () -> executionService.recordExecution(request));

            assertEquals(404, exception.getCode());
            assertTrue(exception.getMessage().contains("不存在"));
        }

        @Test
        @DisplayName("非已生效合同不应记录执行")
        void recordExecution_OnPendingContract_ShouldThrowException() {
            Contract pending = TestDataBuilder.buildPendingApprovalContract();
            ExecutionRequest request = TestDataBuilder.buildExecutionRequest(pending.getContractId(), 50);

            when(contractRepository.findByContractId(pending.getContractId()))
                    .thenReturn(Optional.of(pending));

            ContractException exception = assertThrows(ContractException.class,
                    () -> executionService.recordExecution(request));

            assertEquals(400, exception.getCode());
            assertTrue(exception.getMessage().contains("状态") || exception.getMessage().contains("不允许"));
        }

        @Test
        @DisplayName("执行记录金额应正确保存")
        void recordExecution_WithAmount_ShouldSaveCorrectly() {
            Contract approved = TestDataBuilder.buildApprovedContract();
            ExecutionRequest request = TestDataBuilder.buildExecutionRequest(approved.getContractId(), 50);
            request.setExecutionAmount(new BigDecimal("25000.00"));

            when(contractRepository.findByContractId(approved.getContractId()))
                    .thenReturn(Optional.of(approved));
            when(executionRecordRepository.save(any(ExecutionRecord.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(contractRepository.save(any(Contract.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            Map<String, Object> result = executionService.recordExecution(request);

            assertNotNull(result);
            verify(executionRecordRepository, times(1)).save(argThat(r ->
                    new BigDecimal("25000.00").compareTo(r.getExecutionAmount()) == 0));
        }
    }

    @Nested
    @DisplayName("执行历史查询测试")
    class ExecutionHistoryTests {

        @Test
        @DisplayName("应能获取合同的执行历史")
        void getExecutionHistory_ShouldReturnRecords() {
            String contractId = TestDataBuilder.TEST_CONTRACT_ID;
            ExecutionRecord record1 = TestDataBuilder.buildPartialExecutionRecord(contractId);
            ExecutionRecord record2 = TestDataBuilder.buildFullExecutionRecord(contractId);

            when(executionRecordRepository.findByContractIdOrderByExecutionTimeDesc(contractId))
                    .thenReturn(Arrays.asList(record1, record2));

            List<ExecutionRecord> history = executionService.getExecutionHistory(contractId);

            assertEquals(2, history.size());
            assertEquals(contractId, history.get(0).getContractId());
        }

        @Test
        @DisplayName("无执行记录时应返回空列表")
        void getExecutionHistory_WithNoRecords_ShouldReturnEmpty() {
            String contractId = TestDataBuilder.TEST_CONTRACT_ID;

            when(executionRecordRepository.findByContractIdOrderByExecutionTimeDesc(contractId))
                    .thenReturn(Collections.emptyList());

            List<ExecutionRecord> history = executionService.getExecutionHistory(contractId);

            assertTrue(history.isEmpty());
        }

        @Test
        @DisplayName("应能获取当前执行进度")
        void getCurrentProgress_WithRecords_ShouldReturnMaxProgress() {
            String contractId = TestDataBuilder.TEST_CONTRACT_ID;

            when(executionRecordRepository.findMaxProgressByContractId(contractId)).thenReturn(75);

            Integer progress = executionService.getCurrentProgress(contractId);

            assertEquals(75, progress);
        }

        @Test
        @DisplayName("无执行记录时当前进度应为0")
        void getCurrentProgress_WithNoRecords_ShouldReturnZero() {
            String contractId = TestDataBuilder.TEST_CONTRACT_ID;

            when(executionRecordRepository.findMaxProgressByContractId(contractId)).thenReturn(null);

            Integer progress = executionService.getCurrentProgress(contractId);

            assertEquals(0, progress);
        }
    }
}
