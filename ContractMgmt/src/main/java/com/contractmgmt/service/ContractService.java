package com.contractmgmt.service;

import cn.hutool.core.util.IdUtil;
import com.contractmgmt.config.ContractConfig;
import com.contractmgmt.dto.CreateContractRequest;
import com.contractmgmt.entity.*;
import com.contractmgmt.exception.ContractException;
import com.contractmgmt.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ContractService {

    private static final Logger logger = LoggerFactory.getLogger(ContractService.class);

    private final ContractRepository contractRepository;
    private final ApprovalService approvalService;
    private final ApprovalFlowService approvalFlowService;
    private final StatisticsService statisticsService;
    private final AsyncReminderService asyncReminderService;
    private final HistoryService historyService;
    private final ContractConfig contractConfig;

    public ContractService(
            ContractRepository contractRepository,
            ApprovalService approvalService,
            ApprovalFlowService approvalFlowService,
            StatisticsService statisticsService,
            AsyncReminderService asyncReminderService,
            HistoryService historyService,
            ContractConfig contractConfig) {
        this.contractRepository = contractRepository;
        this.approvalService = approvalService;
        this.approvalFlowService = approvalFlowService;
        this.statisticsService = statisticsService;
        this.asyncReminderService = asyncReminderService;
        this.historyService = historyService;
        this.contractConfig = contractConfig;
    }

    @Transactional
    public Map<String, Object> createContract(CreateContractRequest request) {
        validateContractData(request);

        String contractId = generateContractId();

        Contract contract = buildContract(contractId, request);
        contract = contractRepository.save(contract);
        logger.info("合同创建成功: {}", contractId);

        ApprovalFlowService.FlowContext flowContext = approvalFlowService.resolveApprovalFlow(contract);
        List<String> approvers = flowContext.getApprovers();

        if (approvers == null || approvers.isEmpty()) {
            throw new ContractException(400, "审批流程缺失，无可用审批人员");
        }

        String primaryApprover = flowContext.getPrimaryApprover();

        ApprovalRecord pendingApproval = new ApprovalRecord();
        pendingApproval.setApprovalId("approval_" + IdUtil.getSnowflakeNextIdStr());
        pendingApproval.setContractId(contractId);
        pendingApproval.setApprovalType("create");
        pendingApproval.setApprovalStatus("pending");
        pendingApproval.setApprover(primaryApprover != null ? primaryApprover : "system");
        pendingApproval.setApprovalComment("待审批 - 流程: " + flowContext.getFlowName());
        pendingApproval.setApprovalTime(LocalDateTime.now());

        for (String approver : approvers) {
            logger.info("发送审批通知给审批人: {}, 合同: {}, 流程: {}",
                    approver, contractId, flowContext.getFlowName());
        }

        approvalService.saveApprovalRecord(pendingApproval);

        statisticsService.incrementTotalCount();
        statisticsService.incrementPendingCount();

        if (request.getContractEnd() != null) {
            asyncReminderService.createExpireReminderAsync(contractId, request.getContractEnd());
            logger.debug("已异步提交到期提醒配置任务: {}", contractId);
        }

        String operator = request.getOperator() != null ? request.getOperator() : "system";
        historyService.recordHistory(contractId, "contract", "create", operator,
                "创建合同: " + request.getContractName() + ", 流程: " + flowContext.getFlowName(),
                null, null);

        Map<String, Object> result = new HashMap<>();
        result.put("contract_id", contractId);
        result.put("status", "pending_approval");
        result.put("approval_flow", flowContext.getFlowName());
        result.put("approval_count", approvers.size());
        result.put("primary_approver", primaryApprover);
        result.put("urgency_level", contract.getUrgencyLevel());
        return result;
    }

    public Contract getContract(String contractId) {
        return contractRepository.findByContractId(contractId)
                .orElseThrow(() -> new ContractException(404, "合同不存在: " + contractId));
    }

    public List<Contract> listContractsByStatus(String status) {
        if (status != null && !status.isEmpty()) {
            return contractRepository.findByContractStatus(status);
        }
        return contractRepository.findAll();
    }

    public void updateContractStatus(String contractId, String status) {
        Contract contract = getContract(contractId);
        contract.setContractStatus(status);
        contract.setUpdatedAt(LocalDateTime.now());
        contractRepository.save(contract);
    }

    public Contract updateContractExecution(String contractId, Integer progress, String executionStatus) {
        Contract contract = getContract(contractId);
        contract.setExecutionProgress(progress);
        if (executionStatus != null) {
            contract.setExecutionStatus(executionStatus);
        }
        contract.setLastExecutionUpdate(LocalDateTime.now());
        contract.setUpdatedAt(LocalDateTime.now());
        return contractRepository.save(contract);
    }

    private void validateContractData(CreateContractRequest request) {
        if (request.getContractAmount() == null ||
                request.getContractAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ContractException(400, "合同金额异常，必须大于0");
        }

        if (request.getContractStart() != null && request.getContractEnd() != null) {
            if (request.getContractStart().isAfter(request.getContractEnd())) {
                throw new ContractException(400, "合同期限无效，开始日期不能晚于结束日期");
            }
        }
    }

    private Contract buildContract(String contractId, CreateContractRequest request) {
        Contract contract = new Contract();
        contract.setContractId(contractId);
        contract.setContractName(request.getContractName());
        contract.setContractType(request.getContractType());
        contract.setUrgencyLevel(request.getUrgencyLevel() != null ?
                request.getUrgencyLevel() : "normal");
        contract.setContractAmount(request.getContractAmount());
        contract.setContractStart(request.getContractStart());
        contract.setContractEnd(request.getContractEnd());
        contract.setPartyA(request.getPartyA());
        contract.setPartyB(request.getPartyB());
        contract.setContractStatus("pending_approval");
        contract.setExecutionProgress(0);
        contract.setExecutionStatus("pending");
        contract.setActivityLevel("normal");
        return contract;
    }

    private String generateContractId() {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String uuid = IdUtil.getSnowflakeNextIdStr();
        return "contract_" + dateStr + "_" + uuid.substring(uuid.length() - 6);
    }

    public List<Contract> getContractsExpiringBetween(LocalDate startDate, LocalDate endDate) {
        return contractRepository.findContractsExpiringBetween(startDate, endDate,
                List.of("approved", "active"));
    }

    public List<Contract> getExpiredContracts(LocalDate date) {
        return contractRepository.findExpiredContracts(date, List.of("approved", "active"));
    }

    public Contract saveContract(Contract contract) {
        return contractRepository.save(contract);
    }

    public Optional<Contract> findById(String contractId) {
        return contractRepository.findByContractId(contractId);
    }

    public void updateContractUrgency(String contractId, String urgencyLevel) {
        Contract contract = getContract(contractId);
        if (urgencyLevel != null && !urgencyLevel.isEmpty()) {
            contract.setUrgencyLevel(urgencyLevel);
            contract.setUpdatedAt(LocalDateTime.now());
            contractRepository.save(contract);
            logger.info("更新合同紧急程度: {} -> {}", contractId, urgencyLevel);
        }
    }
}
