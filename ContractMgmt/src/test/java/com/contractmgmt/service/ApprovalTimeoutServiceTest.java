package com.contractmgmt.service;

import com.contractmgmt.builder.TestDataBuilder;
import com.contractmgmt.config.ContractConfig;
import com.contractmgmt.entity.ApprovalRecord;
import com.contractmgmt.entity.Contract;
import com.contractmgmt.entity.ReminderConfig;
import com.contractmgmt.repository.ApprovalRecordRepository;
import com.contractmgmt.repository.ContractRepository;
import com.contractmgmt.repository.ReminderConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("审批超时提醒单元测试")
class ApprovalTimeoutServiceTest {

    @Mock
    private ApprovalRecordRepository approvalRecordRepository;

    @Mock
    private ContractRepository contractRepository;

    @Mock
    private ReminderConfigRepository reminderConfigRepository;

    @Mock
    private ContractConfig contractConfig;

    @Mock
    private ContractConfig.Approval approvalConfig;

    @Mock
    private ContractConfig.Timeout timeoutConfig;

    @InjectMocks
    private ApprovalTimeoutService approvalTimeoutService;

    @BeforeEach
    void setUp() {
        when(contractConfig.getApproval()).thenReturn(approvalConfig);
        when(approvalConfig.getTimeout()).thenReturn(timeoutConfig);
        when(timeoutConfig.getEnabled()).thenReturn(true);
    }

    @Nested
    @DisplayName("超时检测测试")
    class TimeoutDetectionTests {

        @Test
        @DisplayName("超时检测已禁用时不应执行检查")
        void checkApprovalTimeouts_WhenDisabled_ShouldReturnEmpty() {
            when(timeoutConfig.getEnabled()).thenReturn(false);

            List<ApprovalTimeoutService.TimeoutCheckResult> results =
                    approvalTimeoutService.checkApprovalTimeouts();

            assertTrue(results.isEmpty());
            verify(contractRepository, never()).findByContractStatus(anyString());
        }

        @Test
        @DisplayName("超时配置为null时不应执行检查")
        void checkApprovalTimeouts_WhenNullConfig_ShouldReturnEmpty() {
            when(approvalConfig.getTimeout()).thenReturn(null);

            List<ApprovalTimeoutService.TimeoutCheckResult> results =
                    approvalTimeoutService.checkApprovalTimeouts();

            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("没有待审批合同时应返回空结果")
        void checkApprovalTimeouts_WithNoPendingContracts_ShouldReturnEmpty() {
            when(contractRepository.findByContractStatus("pending_approval"))
                    .thenReturn(Collections.emptyList());

            List<ApprovalTimeoutService.TimeoutCheckResult> results =
                    approvalTimeoutService.checkApprovalTimeouts();

            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("正常状态合同不应触发警告")
        void checkApprovalTimeouts_WithNormalContract_ShouldBeNormal() {
            Contract normalContract = TestDataBuilder.buildPendingApprovalContract();
            ApprovalRecord normalApproval = TestDataBuilder.buildPendingApprovalRecord(
                    normalContract.getContractId());
            normalApproval.setApprovalTime(LocalDateTime.now().minusHours(10));

            when(contractRepository.findByContractStatus("pending_approval"))
                    .thenReturn(Arrays.asList(normalContract));
            when(approvalRecordRepository.findByContractIdAndApprovalType(
                    eq(normalContract.getContractId()), eq("create")))
                    .thenReturn(Arrays.asList(normalApproval));
            when(timeoutConfig.getTimeoutByUrgency("normal")).thenReturn(48);

            List<ApprovalTimeoutService.TimeoutCheckResult> results =
                    approvalTimeoutService.checkApprovalTimeouts();

            assertEquals(1, results.size());
            assertEquals(ApprovalTimeoutService.TimeoutStatus.NORMAL, results.get(0).status);
            verify(reminderConfigRepository, never()).save(any());
        }

        @Test
        @DisplayName("80%超时阈值时应触发警告状态")
        void checkApprovalTimeouts_At80PercentThreshold_ShouldBeWarning() {
            Contract urgentContract = TestDataBuilder.buildContract();
            urgentContract.setContractName("采购合同-重要");
            ApprovalRecord urgentApproval = TestDataBuilder.buildPendingApprovalRecord(
                    urgentContract.getContractId());
            urgentApproval.setApprovalTime(LocalDateTime.now().minusHours(20));

            when(contractRepository.findByContractStatus("pending_approval"))
                    .thenReturn(Arrays.asList(urgentContract));
            when(approvalRecordRepository.findByContractIdAndApprovalType(
                    eq(urgentContract.getContractId()), eq("create")))
                    .thenReturn(Arrays.asList(urgentApproval));
            when(timeoutConfig.getTimeoutByUrgency("urgent")).thenReturn(24);

            List<ApprovalTimeoutService.TimeoutCheckResult> results =
                    approvalTimeoutService.checkApprovalTimeouts();

            assertEquals(1, results.size());
            assertEquals(ApprovalTimeoutService.TimeoutStatus.WARNING, results.get(0).status);
            verify(reminderConfigRepository, times(1)).save(any(ReminderConfig.class));
        }

        @Test
        @DisplayName("超过超时阈值时应触发超时状态")
        void checkApprovalTimeouts_ExceedingThreshold_ShouldBeTimeout() {
            Contract criticalContract = TestDataBuilder.buildContract();
            criticalContract.setContractName("采购合同-紧急合同");
            ApprovalRecord criticalApproval = TestDataBuilder.buildPendingApprovalRecord(
                    criticalContract.getContractId());
            criticalApproval.setApprovalTime(LocalDateTime.now().minusHours(15));

            when(contractRepository.findByContractStatus("pending_approval"))
                    .thenReturn(Arrays.asList(criticalContract));
            when(approvalRecordRepository.findByContractIdAndApprovalType(
                    eq(criticalContract.getContractId()), eq("create")))
                    .thenReturn(Arrays.asList(criticalApproval));
            when(timeoutConfig.getTimeoutByUrgency("critical")).thenReturn(12);

            List<ApprovalTimeoutService.TimeoutCheckResult> results =
                    approvalTimeoutService.checkApprovalTimeouts();

            assertEquals(1, results.size());
            assertEquals(ApprovalTimeoutService.TimeoutStatus.TIMEOUT, results.get(0).status);
            assertEquals("critical", results.get(0).urgency);
            verify(reminderConfigRepository, times(1)).save(any(ReminderConfig.class));
        }
    }

    @Nested
    @DisplayName("紧急程度阈值差异测试")
    class UrgencyThresholdTests {

        @Test
        @DisplayName("普通合同超时阈值应为48小时")
        void getTimeoutHoursByUrgency_Normal_ShouldBe48() {
            when(timeoutConfig.getTimeoutByUrgency("normal")).thenReturn(48);

            int hours = approvalTimeoutService.getTimeoutHoursByUrgency("normal");

            assertEquals(48, hours);
        }

        @Test
        @DisplayName("重要合同超时阈值应为24小时")
        void getTimeoutHoursByUrgency_Urgent_ShouldBe24() {
            when(timeoutConfig.getTimeoutByUrgency("urgent")).thenReturn(24);

            int hours = approvalTimeoutService.getTimeoutHoursByUrgency("urgent");

            assertEquals(24, hours);
        }

        @Test
        @DisplayName("紧急合同超时阈值应为12小时")
        void getTimeoutHoursByUrgency_Critical_ShouldBe12() {
            when(timeoutConfig.getTimeoutByUrgency("critical")).thenReturn(12);

            int hours = approvalTimeoutService.getTimeoutHoursByUrgency("critical");

            assertEquals(12, hours);
        }

        @Test
        @DisplayName("null紧急程度应使用默认值")
        void getTimeoutHoursByUrgency_Null_ShouldUseDefault() {
            when(timeoutConfig.getTimeoutByUrgency(null)).thenReturn(48);

            int hours = approvalTimeoutService.getTimeoutHoursByUrgency(null);

            assertEquals(48, hours);
        }

        @Test
        @DisplayName("紧急合同应比普通合同更早触发超时")
        void getTimeoutHoursByUrgency_CriticalShouldBeSmallerThanNormal() {
            ContractConfig.Timeout realTimeout = new ContractConfig.Timeout();
            when(timeoutConfig.getTimeoutByUrgency("critical")).thenReturn(realTimeout.getCriticalHours());
            when(timeoutConfig.getTimeoutByUrgency("normal")).thenReturn(realTimeout.getNormalHours());

            int criticalHours = approvalTimeoutService.getTimeoutHoursByUrgency("critical");
            int normalHours = approvalTimeoutService.getTimeoutHoursByUrgency("normal");

            assertTrue(criticalHours < normalHours,
                    "紧急合同超时阈值应小于普通合同");
        }
    }

    @Nested
    @DisplayName("超时提醒发送测试")
    class TimeoutReminderTests {

        @Test
        @DisplayName("警告状态应发送超时提醒")
        void checkApprovalTimeouts_WarningStatus_ShouldSendReminder() {
            Contract contract = TestDataBuilder.buildPendingApprovalContract();
            ApprovalRecord approval = TestDataBuilder.buildPendingApprovalRecord(contract.getContractId());
            approval.setApprovalTime(LocalDateTime.now().minusHours(40));

            when(contractRepository.findByContractStatus("pending_approval"))
                    .thenReturn(Arrays.asList(contract));
            when(approvalRecordRepository.findByContractIdAndApprovalType(
                    eq(contract.getContractId()), eq("create")))
                    .thenReturn(Arrays.asList(approval));
            when(timeoutConfig.getTimeoutByUrgency("normal")).thenReturn(48);
            when(reminderConfigRepository.save(any(ReminderConfig.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            approvalTimeoutService.checkApprovalTimeouts();

            verify(reminderConfigRepository, times(1)).save(argThat(r ->
                    "approval_timeout".equals(r.getReminderType()) &&
                            "pending".equals(r.getReminderStatus()) &&
                            "email".equals(r.getReminderChannel())));
        }

        @Test
        @DisplayName("超时状态应发送超时提醒")
        void checkApprovalTimeouts_TimeoutStatus_ShouldSendReminder() {
            Contract contract = TestDataBuilder.buildPendingApprovalContract();
            ApprovalRecord approval = TestDataBuilder.buildPendingApprovalRecord(contract.getContractId());
            approval.setApprovalTime(LocalDateTime.now().minusHours(50));

            when(contractRepository.findByContractStatus("pending_approval"))
                    .thenReturn(Arrays.asList(contract));
            when(approvalRecordRepository.findByContractIdAndApprovalType(
                    eq(contract.getContractId()), eq("create")))
                    .thenReturn(Arrays.asList(approval));
            when(timeoutConfig.getTimeoutByUrgency("normal")).thenReturn(48);

            approvalTimeoutService.checkApprovalTimeouts();

            verify(reminderConfigRepository, times(1)).save(any(ReminderConfig.class));
        }

        @Test
        @DisplayName("没有审批记录时不应触发提醒")
        void checkApprovalTimeouts_NoApprovalRecords_ShouldNotSendReminder() {
            Contract contract = TestDataBuilder.buildPendingApprovalContract();

            when(contractRepository.findByContractStatus("pending_approval"))
                    .thenReturn(Arrays.asList(contract));
            when(approvalRecordRepository.findByContractIdAndApprovalType(
                    eq(contract.getContractId()), eq("create")))
                    .thenReturn(Collections.emptyList());

            approvalTimeoutService.checkApprovalTimeouts();

            verify(reminderConfigRepository, never()).save(any());
        }

        @Test
        @DisplayName("已处理的审批不应触发提醒")
        void checkApprovalTimeouts_AlreadyProcessed_ShouldNotSendReminder() {
            Contract contract = TestDataBuilder.buildPendingApprovalContract();
            ApprovalRecord approved = TestDataBuilder.buildApprovedApprovalRecord(contract.getContractId());

            when(contractRepository.findByContractStatus("pending_approval"))
                    .thenReturn(Arrays.asList(contract));
            when(approvalRecordRepository.findByContractIdAndApprovalType(
                    eq(contract.getContractId()), eq("create")))
                    .thenReturn(Arrays.asList(approved));

            approvalTimeoutService.checkApprovalTimeouts();

            verify(reminderConfigRepository, never()).save(any());
        }
    }
}
