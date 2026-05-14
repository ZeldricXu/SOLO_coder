package com.contractmgmt.service;

import com.contractmgmt.builder.TestDataBuilder;
import com.contractmgmt.config.ContractConfig;
import com.contractmgmt.dto.CreateContractRequest;
import com.contractmgmt.entity.Contract;
import com.contractmgmt.exception.ContractException;
import com.contractmgmt.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("合同管理模块单元测试")
class ContractServiceTest {

    @Mock
    private ContractRepository contractRepository;

    @Mock
    private ApprovalService approvalService;

    @Mock
    private StatisticsService statisticsService;

    @Mock
    private ReminderService reminderService;

    @Mock
    private HistoryService historyService;

    @Mock
    private ContractConfig contractConfig;

    @Mock
    private ContractConfig.Approval approvalConfig;

    @InjectMocks
    private ContractService contractService;

    private final List<String> DEFAULT_APPROVERS = Arrays.asList(
            "user_manager_01", "user_manager_02", "user_manager_03");

    @BeforeEach
    void setUp() {
        when(contractConfig.getApproval()).thenReturn(approvalConfig);
        when(approvalConfig.getDefaultApprovers()).thenReturn(DEFAULT_APPROVERS);
    }

    @Nested
    @DisplayName("合同创建测试")
    class ContractCreationTests {

        @Test
        @DisplayName("应成功创建有效的合同")
        void createContract_WithValidData_ShouldSucceed() {
            CreateContractRequest request = TestDataBuilder.buildCreateContractRequest();
            when(contractRepository.save(any(Contract.class))).thenAnswer(invocation -> {
                Contract saved = invocation.getArgument(0);
                return saved;
            });

            Map<String, Object> result = contractService.createContract(request);

            assertNotNull(result, "返回结果不能为空");
            assertTrue(result.containsKey("contract_id"), "应包含合同ID");
            assertTrue(result.containsKey("status"), "应包含状态");
            assertEquals("pending_approval", result.get("status"), "状态应为待审批");

            verify(contractRepository, times(1)).save(any(Contract.class));
            verify(approvalService, times(1)).saveApprovalRecord(any());
            verify(statisticsService, times(1)).incrementTotalCount();
            verify(statisticsService, times(1)).incrementPendingCount();
            verify(reminderService, times(1)).createExpireReminder(anyString(), any(LocalDate.class));
            verify(historyService, times(1)).recordHistory(anyString(), eq("contract"),
                    eq("create"), anyString(), anyString(), isNull(), isNull());
        }

        @Test
        @DisplayName("合同金额为负数时应抛出异常")
        void createContract_WithNegativeAmount_ShouldThrowException() {
            CreateContractRequest request = TestDataBuilder.buildInvalidAmountRequest();

            ContractException exception = assertThrows(ContractException.class,
                    () -> contractService.createContract(request));

            assertEquals(400, exception.getCode());
            assertTrue(exception.getMessage().contains("金额"));
            verify(contractRepository, never()).save(any());
        }

        @Test
        @DisplayName("合同金额为零时应抛出异常")
        void createContract_WithZeroAmount_ShouldThrowException() {
            CreateContractRequest request = TestDataBuilder.buildCreateContractRequest();
            request.setContractAmount(BigDecimal.ZERO);

            ContractException exception = assertThrows(ContractException.class,
                    () -> contractService.createContract(request));

            assertEquals(400, exception.getCode());
            assertTrue(exception.getMessage().contains("金额"));
        }

        @Test
        @DisplayName("开始日期晚于结束日期时应抛出异常")
        void createContract_WithInvalidDateRange_ShouldThrowException() {
            CreateContractRequest request = TestDataBuilder.buildInvalidDateRequest();

            ContractException exception = assertThrows(ContractException.class,
                    () -> contractService.createContract(request));

            assertEquals(400, exception.getCode());
            assertTrue(exception.getMessage().contains("期限"));
        }

        @Test
        @DisplayName("未配置审批人员时应抛出异常")
        void createContract_WithoutApprovers_ShouldThrowException() {
            when(approvalConfig.getDefaultApprovers()).thenReturn(Arrays.asList());
            CreateContractRequest request = TestDataBuilder.buildCreateContractRequest();

            ContractException exception = assertThrows(ContractException.class,
                    () -> contractService.createContract(request));

            assertEquals(400, exception.getCode());
            assertTrue(exception.getMessage().contains("审批流程"));
        }

        @Test
        @DisplayName("审批人员配置为null时应抛出异常")
        void createContract_WithNullApprovers_ShouldThrowException() {
            when(approvalConfig.getDefaultApprovers()).thenReturn(null);
            CreateContractRequest request = TestDataBuilder.buildCreateContractRequest();

            ContractException exception = assertThrows(ContractException.class,
                    () -> contractService.createContract(request));

            assertEquals(400, exception.getCode());
            assertTrue(exception.getMessage().contains("审批流程"));
        }
    }

    @Nested
    @DisplayName("合同查询测试")
    class ContractQueryTests {

        @Test
        @DisplayName("应成功获取存在的合同")
        void getContract_WithExistingId_ShouldReturnContract() {
            Contract expected = TestDataBuilder.buildContract();
            when(contractRepository.findByContractId(expected.getContractId()))
                    .thenReturn(Optional.of(expected));

            Contract result = contractService.getContract(expected.getContractId());

            assertNotNull(result);
            assertEquals(expected.getContractId(), result.getContractId());
            assertEquals(expected.getContractName(), result.getContractName());
        }

        @Test
        @DisplayName("获取不存在的合同应抛出异常")
        void getContract_WithNonExistingId_ShouldThrowException() {
            String nonExistingId = "non_existing_001";
            when(contractRepository.findByContractId(nonExistingId)).thenReturn(Optional.empty());

            ContractException exception = assertThrows(ContractException.class,
                    () -> contractService.getContract(nonExistingId));

            assertEquals(404, exception.getCode());
            assertTrue(exception.getMessage().contains("不存在"));
        }

        @Test
        @DisplayName("按状态查询合同应正确过滤")
        void listContractsByStatus_WithStatus_ShouldFilterCorrectly() {
            Contract approved = TestDataBuilder.buildApprovedContract();
            Contract pending = TestDataBuilder.buildPendingApprovalContract();

            when(contractRepository.findByContractStatus("approved"))
                    .thenReturn(Arrays.asList(approved));
            when(contractRepository.findAll()).thenReturn(Arrays.asList(approved, pending));

            List<Contract> approvedList = contractService.listContractsByStatus("approved");
            assertEquals(1, approvedList.size());
            assertEquals("approved", approvedList.get(0).getContractStatus());

            List<Contract> allList = contractService.listContractsByStatus(null);
            assertEquals(2, allList.size());

            List<Contract> emptyStatusList = contractService.listContractsByStatus("");
            assertEquals(2, emptyStatusList.size());
        }
    }

    @Nested
    @DisplayName("合同状态流转测试")
    class ContractStatusFlowTests {

        @Test
        @DisplayName("待审批->已生效状态流转")
        void statusFlow_PendingToApproved_ShouldUpdateCorrectly() {
            Contract contract = TestDataBuilder.buildPendingApprovalContract();
            when(contractRepository.findByContractId(contract.getContractId()))
                    .thenReturn(Optional.of(contract));
            when(contractRepository.save(any(Contract.class))).thenAnswer(invocation -> invocation.getArgument(0));

            contractService.updateContractStatus(contract.getContractId(), "approved");

            verify(contractRepository, times(1)).save(argThat(c ->
                    "approved".equals(c.getContractStatus()) && c.getUpdatedAt() != null));
        }

        @Test
        @DisplayName("已生效->已归档状态流转")
        void statusFlow_ApprovedToArchived_ShouldUpdateCorrectly() {
            Contract contract = TestDataBuilder.buildApprovedContract();
            when(contractRepository.findByContractId(contract.getContractId()))
                    .thenReturn(Optional.of(contract));
            when(contractRepository.save(any(Contract.class))).thenAnswer(invocation -> invocation.getArgument(0));

            contractService.updateContractStatus(contract.getContractId(), "archived");

            verify(contractRepository, times(1)).save(argThat(c ->
                    "archived".equals(c.getContractStatus())));
        }

        @Test
        @DisplayName("执行进度更新应正确计算状态")
        void updateExecutionProgress_WithDifferentProgress_ShouldSetCorrectStatus() {
            Contract contract = TestDataBuilder.buildApprovedContract();
            contract.setExecutionProgress(0);
            contract.setExecutionStatus("in_progress");
            when(contractRepository.findByContractId(contract.getContractId()))
                    .thenReturn(Optional.of(contract));
            when(contractRepository.save(any(Contract.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Contract updated = contractService.updateContractExecution(
                    contract.getContractId(), 100, "completed");

            assertEquals(100, updated.getExecutionProgress());
            assertEquals("completed", updated.getExecutionStatus());
        }
    }

    @Nested
    @DisplayName("异常状态处理测试")
    class ExceptionStatusTests {

        @Test
        @DisplayName("查询已拒绝合同应返回正确状态")
        void getRejectedContract_ShouldReturnCorrectStatus() {
            Contract rejected = TestDataBuilder.buildRejectedContract();
            when(contractRepository.findByContractId(rejected.getContractId()))
                    .thenReturn(Optional.of(rejected));

            Contract result = contractService.getContract(rejected.getContractId());

            assertEquals("rejected", result.getContractStatus());
            assertNull(result.getEffectiveTime());
        }

        @Test
        @DisplayName("查询已归档合同应返回正确状态")
        void getArchivedContract_ShouldReturnCorrectStatus() {
            Contract archived = TestDataBuilder.buildArchivedContract();
            when(contractRepository.findByContractId(archived.getContractId()))
                    .thenReturn(Optional.of(archived));

            Contract result = contractService.getContract(archived.getContractId());

            assertEquals("archived", result.getContractStatus());
            assertNotNull(result.getArchiveTime());
        }
    }

    @Nested
    @DisplayName("到期合同查询测试")
    class ExpiringContractTests {

        @Test
        @DisplayName("应正确查询即将到期的合同")
        void getContractsExpiringBetween_ShouldReturnCorrectContracts() {
            LocalDate start = LocalDate.now();
            LocalDate end = LocalDate.now().plusDays(15);
            Contract expiring = TestDataBuilder.buildExpiringContract(10);

            when(contractRepository.findContractsExpiringBetween(eq(start), eq(end), anyList()))
                    .thenReturn(Arrays.asList(expiring));

            List<Contract> result = contractService.getContractsExpiringBetween(start, end);

            assertEquals(1, result.size());
            assertEquals(expiring.getContractId(), result.get(0).getContractId());
        }

        @Test
        @DisplayName("应正确查询已过期的合同")
        void getExpiredContracts_ShouldReturnCorrectContracts() {
            LocalDate today = LocalDate.now();
            Contract expired = TestDataBuilder.buildExpiredContract();

            when(contractRepository.findExpiredContracts(eq(today), anyList()))
                    .thenReturn(Arrays.asList(expired));

            List<Contract> result = contractService.getExpiredContracts(today);

            assertEquals(1, result.size());
            assertTrue(result.get(0).getContractEnd().isBefore(today) ||
                    result.get(0).getContractEnd().isEqual(today));
        }
    }
}
