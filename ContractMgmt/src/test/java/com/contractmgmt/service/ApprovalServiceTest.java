package com.contractmgmt.service;

import com.contractmgmt.builder.TestDataBuilder;
import com.contractmgmt.config.ContractConfig;
import com.contractmgmt.dto.ApprovalRequest;
import com.contractmgmt.entity.ApprovalRecord;
import com.contractmgmt.entity.Contract;
import com.contractmgmt.exception.ContractException;
import com.contractmgmt.repository.ApprovalRecordRepository;
import com.contractmgmt.repository.ContractRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("审批模块单元测试")
class ApprovalServiceTest {

    @Mock
    private ApprovalRecordRepository approvalRecordRepository;

    @Mock
    private ContractRepository contractRepository;

    @Mock
    private ExecutionService executionService;

    @Mock
    private StatisticsService statisticsService;

    @Mock
    private HistoryService historyService;

    @Mock
    private ContractConfig contractConfig;

    @Mock
    private ContractConfig.Approval approvalConfig;

    @InjectMocks
    private ApprovalService approvalService;

    private final List<String> DEFAULT_APPROVERS = Arrays.asList(
            "user_manager_01", "user_manager_02", "user_manager_03");

    @BeforeEach
    void setUp() {
        when(contractConfig.getApproval()).thenReturn(approvalConfig);
        when(approvalConfig.getDefaultApprovers()).thenReturn(DEFAULT_APPROVERS);
    }

    @Nested
    @DisplayName("审批通过测试")
    class ApprovalPassTests {

        @Test
        @DisplayName("待审批合同应能成功审批通过")
        void processApproval_WithValidApprover_Approve_ShouldSucceed() {
            Contract pending = TestDataBuilder.buildPendingApprovalContract();
            ApprovalRequest request = TestDataBuilder.buildApproveRequest(pending.getContractId());

            when(contractRepository.findByContractId(pending.getContractId()))
                    .thenReturn(Optional.of(pending));
            when(approvalRecordRepository.save(any(ApprovalRecord.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(contractRepository.save(any(Contract.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            Map<String, Object> result = approvalService.processApproval(request);

            assertNotNull(result);
            assertEquals("approved", result.get("status"));

            verify(statisticsService, times(1)).decrementPendingCount();
            verify(statisticsService, times(1)).incrementActiveCount();
            verify(statisticsService, times(1)).addActiveAmount(any());
            verify(executionService, times(1)).initializeExecutionTracking(anyString());
            verify(historyService, times(1)).recordHistory(
                    eq(pending.getContractId()), eq("approval"), eq("approve"),
                    anyString(), anyString(), eq("pending_approval"), eq("approved"));
        }

        @Test
        @DisplayName("审批通过后合同状态应为已生效")
        void processApproval_Approve_ShouldSetApprovedStatus() {
            Contract pending = TestDataBuilder.buildPendingApprovalContract();
            ApprovalRequest request = TestDataBuilder.buildApproveRequest(pending.getContractId());

            when(contractRepository.findByContractId(pending.getContractId()))
                    .thenReturn(Optional.of(pending));
            when(approvalRecordRepository.save(any(ApprovalRecord.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(contractRepository.save(any(Contract.class))).thenAnswer(invocation -> {
                Contract saved = invocation.getArgument(0);
                return saved;
            });

            approvalService.processApproval(request);

            verify(contractRepository, times(1)).save(argThat(c ->
                    "approved".equals(c.getContractStatus()) &&
                            c.getEffectiveTime() != null &&
                            "in_progress".equals(c.getExecutionStatus())));
        }
    }

    @Nested
    @DisplayName("审批拒绝测试")
    class ApprovalRejectTests {

        @Test
        @DisplayName("待审批合同应能成功审批拒绝")
        void processApproval_WithValidApprover_Reject_ShouldSucceed() {
            Contract pending = TestDataBuilder.buildPendingApprovalContract();
            ApprovalRequest request = TestDataBuilder.buildRejectRequest(pending.getContractId());

            when(contractRepository.findByContractId(pending.getContractId()))
                    .thenReturn(Optional.of(pending));
            when(approvalRecordRepository.save(any(ApprovalRecord.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(contractRepository.save(any(Contract.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            Map<String, Object> result = approvalService.processApproval(request);

            assertNotNull(result);
            assertEquals("rejected", result.get("status"));

            verify(statisticsService, times(1)).decrementPendingCount();
            verify(statisticsService, times(1)).incrementRejectedCount();
            verify(statisticsService, never()).incrementActiveCount();
            verify(executionService, never()).initializeExecutionTracking(anyString());
            verify(historyService, times(1)).recordHistory(
                    eq(pending.getContractId()), eq("approval"), eq("reject"),
                    anyString(), anyString(), eq("pending_approval"), eq("rejected"));
        }

        @Test
        @DisplayName("审批拒绝后合同状态应为已拒绝")
        void processApproval_Reject_ShouldSetRejectedStatus() {
            Contract pending = TestDataBuilder.buildPendingApprovalContract();
            ApprovalRequest request = TestDataBuilder.buildRejectRequest(pending.getContractId());

            when(contractRepository.findByContractId(pending.getContractId()))
                    .thenReturn(Optional.of(pending));
            when(approvalRecordRepository.save(any(ApprovalRecord.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(contractRepository.save(any(Contract.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            approvalService.processApproval(request);

            verify(contractRepository, times(1)).save(argThat(c ->
                    "rejected".equals(c.getContractStatus())));
        }
    }

    @Nested
    @DisplayName("审批权限校验测试")
    class ApprovalPermissionTests {

        @Test
        @DisplayName("无效审批人员应抛出权限异常")
        void processApproval_WithInvalidApprover_ShouldThrowException() {
            Contract pending = TestDataBuilder.buildPendingApprovalContract();
            ApprovalRequest request = TestDataBuilder.buildInvalidApproverRequest(pending.getContractId());

            when(contractRepository.findByContractId(pending.getContractId()))
                    .thenReturn(Optional.of(pending));

            ContractException exception = assertThrows(ContractException.class,
                    () -> approvalService.processApproval(request));

            assertEquals(403, exception.getCode());
            assertTrue(exception.getMessage().contains("无效") || exception.getMessage().contains("权限"));

            verify(approvalRecordRepository, never()).save(any());
            verify(contractRepository, never()).save(any(Contract.class));
        }

        @Test
        @DisplayName("审批人员配置为空应抛出异常")
        void processApproval_WithEmptyApprovers_ShouldThrowException() {
            Contract pending = TestDataBuilder.buildPendingApprovalContract();
            ApprovalRequest request = TestDataBuilder.buildApproveRequest(pending.getContractId());

            when(contractRepository.findByContractId(pending.getContractId()))
                    .thenReturn(Optional.of(pending));
            when(approvalConfig.getDefaultApprovers()).thenReturn(Arrays.asList());

            ContractException exception = assertThrows(ContractException.class,
                    () -> approvalService.processApproval(request));

            assertTrue(exception.getMessage().contains("配置") || exception.getMessage().contains("列表"));
        }
    }

    @Nested
    @DisplayName("状态校验测试")
    class StatusValidationTests {

        @Test
        @DisplayName("已生效合同不应重复审批")
        void processApproval_OnApprovedContract_ShouldThrowException() {
            Contract approved = TestDataBuilder.buildApprovedContract();
            ApprovalRequest request = TestDataBuilder.buildApproveRequest(approved.getContractId());

            when(contractRepository.findByContractId(approved.getContractId()))
                    .thenReturn(Optional.of(approved));

            ContractException exception = assertThrows(ContractException.class,
                    () -> approvalService.processApproval(request));

            assertEquals(400, exception.getCode());
            assertTrue(exception.getMessage().contains("不允许") || exception.getMessage().contains("状态"));
        }

        @Test
        @DisplayName("已拒绝合同不应重复审批")
        void processApproval_OnRejectedContract_ShouldThrowException() {
            Contract rejected = TestDataBuilder.buildRejectedContract();
            ApprovalRequest request = TestDataBuilder.buildApproveRequest(rejected.getContractId());

            when(contractRepository.findByContractId(rejected.getContractId()))
                    .thenReturn(Optional.of(rejected));

            ContractException exception = assertThrows(ContractException.class,
                    () -> approvalService.processApproval(request));

            assertEquals(400, exception.getCode());
        }

        @Test
        @DisplayName("不存在的合同应抛出异常")
        void processApproval_WithNonExistingContract_ShouldThrowException() {
            String nonExistingId = "non_existing_001";
            ApprovalRequest request = TestDataBuilder.buildApproveRequest(nonExistingId);

            when(contractRepository.findByContractId(nonExistingId)).thenReturn(Optional.empty());

            ContractException exception = assertThrows(ContractException.class,
                    () -> approvalService.processApproval(request));

            assertEquals(404, exception.getCode());
            assertTrue(exception.getMessage().contains("不存在"));
        }
    }

    @Nested
    @DisplayName("审批历史测试")
    class ApprovalHistoryTests {

        @Test
        @DisplayName("应能获取合同的审批历史记录")
        void getApprovalHistory_ShouldReturnRecords() {
            String contractId = TestDataBuilder.TEST_CONTRACT_ID;
            ApprovalRecord record1 = TestDataBuilder.buildApprovedApprovalRecord(contractId);
            ApprovalRecord record2 = TestDataBuilder.buildPendingApprovalRecord(contractId);

            when(approvalRecordRepository.findByContractIdOrderByApprovalTimeDesc(contractId))
                    .thenReturn(Arrays.asList(record1, record2));

            List<ApprovalRecord> history = approvalService.getApprovalHistory(contractId);

            assertEquals(2, history.size());
            assertEquals(contractId, history.get(0).getContractId());
        }

        @Test
        @DisplayName("应能按审批人查询审批记录")
        void getApprovalsByApprover_ShouldFilterCorrectly() {
            String approver = TestDataBuilder.TEST_APPROVER_VALID;
            ApprovalRecord record = TestDataBuilder.buildApprovedApprovalRecord("contract_001");
            record.setApprover(approver);

            when(approvalRecordRepository.findByApprover(approver))
                    .thenReturn(Arrays.asList(record));

            List<ApprovalRecord> records = approvalService.getApprovalsByApprover(approver);

            assertEquals(1, records.size());
            assertEquals(approver, records.get(0).getApprover());
        }
    }
}
