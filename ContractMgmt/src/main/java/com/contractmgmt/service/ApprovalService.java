package com.contractmgmt.service;

import cn.hutool.core.util.IdUtil;
import com.contractmgmt.config.ContractConfig;
import com.contractmgmt.dto.ApprovalRequest;
import com.contractmgmt.entity.*;
import com.contractmgmt.exception.ContractException;
import com.contractmgmt.repository.ApprovalRecordRepository;
import com.contractmgmt.repository.ContractRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ApprovalService {

    private static final Logger logger = LoggerFactory.getLogger(ApprovalService.class);

    private final ApprovalRecordRepository approvalRecordRepository;
    private final ContractRepository contractRepository;
    private final ExecutionService executionService;
    private final StatisticsService statisticsService;
    private final HistoryService historyService;
    private final ApprovalFlowService approvalFlowService;
    private final ContractConfig contractConfig;

    public ApprovalService(
            ApprovalRecordRepository approvalRecordRepository,
            ContractRepository contractRepository,
            ExecutionService executionService,
            StatisticsService statisticsService,
            HistoryService historyService,
            ApprovalFlowService approvalFlowService,
            ContractConfig contractConfig) {
        this.approvalRecordRepository = approvalRecordRepository;
        this.contractRepository = contractRepository;
        this.executionService = executionService;
        this.statisticsService = statisticsService;
        this.historyService = historyService;
        this.approvalFlowService = approvalFlowService;
        this.contractConfig = contractConfig;
    }

    @Transactional
    public Map<String, Object> processApproval(ApprovalRequest request) {
        Contract contract = contractRepository.findByContractId(request.getContractId())
                .orElseThrow(() -> new ContractException(404, "合同不存在: " + request.getContractId()));

        validateApprovalPermission(request.getApprover(), contract);

        if (!"pending_approval".equals(contract.getContractStatus())) {
            throw new ContractException(400, "合同当前状态不允许审批: " + contract.getContractStatus());
        }

        boolean isApproved = "approved".equalsIgnoreCase(request.getApprovalStatus());
        String newStatus = isApproved ? "approved" : "rejected";

        ApprovalFlowService.FlowContext flowContext = approvalFlowService.resolveApprovalFlow(contract);

        ApprovalRecord approvalRecord = new ApprovalRecord();
        approvalRecord.setApprovalId("approval_" + IdUtil.getSnowflakeNextIdStr());
        approvalRecord.setContractId(request.getContractId());
        approvalRecord.setApprovalType(request.getApprovalType() != null ? request.getApprovalType() : "create");
        approvalRecord.setApprovalStatus(newStatus);
        approvalRecord.setApprover(request.getApprover());
        approvalRecord.setApprovalComment(request.getApprovalComment());
        approvalRecord.setApprovalTime(LocalDateTime.now());
        approvalRecordRepository.save(approvalRecord);

        contract.setContractStatus(newStatus);
        contract.setUpdatedAt(LocalDateTime.now());

        if (isApproved) {
            contract.setEffectiveTime(LocalDateTime.now());
            contract.setExecutionStatus("in_progress");
            contract.setExecutionProgress(0);
            contract.setLastExecutionUpdate(LocalDateTime.now());
            logger.info("合同审批通过: {}, 流程: {}", request.getContractId(), flowContext.getFlowName());
        } else {
            logger.info("合同审批被拒绝: {}, 原因: {}", request.getContractId(), request.getApprovalComment());
        }
        contractRepository.save(contract);

        statisticsService.decrementPendingCount();
        if (isApproved) {
            statisticsService.incrementActiveCount();
            statisticsService.addActiveAmount(contract.getContractAmount());
        } else {
            statisticsService.incrementRejectedCount();
        }

        if (isApproved) {
            executionService.initializeExecutionTracking(request.getContractId());
        }

        String action = isApproved ? "approve" : "reject";
        String detail = (isApproved ? "审批通过" : "审批拒绝") +
                " (流程: " + flowContext.getFlowName() + ")";
        if (request.getApprovalComment() != null) {
            detail += ": " + request.getApprovalComment();
        }
        historyService.recordHistory(request.getContractId(), "approval", action,
                request.getApprover(), detail, "pending_approval", newStatus);

        Map<String, Object> result = new HashMap<>();
        result.put("approval_id", approvalRecord.getApprovalId());
        result.put("status", newStatus);
        result.put("flow_name", flowContext.getFlowName());
        result.put("is_approved", isApproved);
        return result;
    }

    private void validateApprovalPermission(String approver, Contract contract) {
        boolean isValid = approvalFlowService.isValidApprover(approver, contract);

        if (!isValid) {
            throw new ContractException(403, "审批人员无效: " + approver);
        }
    }

    public ApprovalRecord saveApprovalRecord(ApprovalRecord record) {
        return approvalRecordRepository.save(record);
    }

    public List<ApprovalRecord> getApprovalHistory(String contractId) {
        return approvalRecordRepository.findByContractIdOrderByApprovalTimeDesc(contractId);
    }

    public List<ApprovalRecord> getApprovalsByApprover(String approver) {
        return approvalRecordRepository.findByApprover(approver);
    }

    public List<ContractConfig.FlowConfig> getAllApprovalFlows() {
        return approvalFlowService.getAllFlowConfigs();
    }

    public ApprovalFlowService.FlowContext getFlowContext(Contract contract) {
        return approvalFlowService.resolveApprovalFlow(contract);
    }
}
