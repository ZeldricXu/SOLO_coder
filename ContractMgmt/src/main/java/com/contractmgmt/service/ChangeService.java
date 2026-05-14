package com.contractmgmt.service;

import cn.hutool.core.util.IdUtil;
import com.contractmgmt.dto.ChangeRequest;
import com.contractmgmt.entity.ChangeRecord;
import com.contractmgmt.entity.Contract;
import com.contractmgmt.exception.ContractException;
import com.contractmgmt.repository.ChangeRecordRepository;
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
public class ChangeService {

    private static final Logger logger = LoggerFactory.getLogger(ChangeService.class);

    private final ChangeRecordRepository changeRecordRepository;
    private final ContractRepository contractRepository;
    private final HistoryService historyService;

    public ChangeService(
            ChangeRecordRepository changeRecordRepository,
            ContractRepository contractRepository,
            HistoryService historyService) {
        this.changeRecordRepository = changeRecordRepository;
        this.contractRepository = contractRepository;
        this.historyService = historyService;
    }

    @Transactional
    public Map<String, Object> createChangeRequest(ChangeRequest request) {
        Contract contract = contractRepository.findByContractId(request.getContractId())
                .orElseThrow(() -> new ContractException(404, "合同不存在: " + request.getContractId()));

        if (!"approved".equals(contract.getContractStatus())) {
            throw new ContractException(400, "只有已生效合同才能变更");
        }

        ChangeRecord changeRecord = new ChangeRecord();
        changeRecord.setChangeId("change_" + IdUtil.getSnowflakeNextIdStr());
        changeRecord.setContractId(request.getContractId());
        changeRecord.setChangeType(request.getChangeType());
        changeRecord.setChangeBefore(request.getChangeBefore());
        changeRecord.setChangeAfter(request.getChangeAfter());
        changeRecord.setChangeBeforeText(request.getChangeBeforeText());
        changeRecord.setChangeAfterText(request.getChangeAfterText());
        changeRecord.setChangeReason(request.getChangeReason());
        changeRecord.setChangeStatus("pending");
        changeRecord.setChangeTime(LocalDateTime.now());
        changeRecordRepository.save(changeRecord);

        String operator = request.getOperator() != null ? request.getOperator() : "system";
        historyService.recordHistory(request.getContractId(), "change", "create",
                operator, "创建变更申请: " + request.getChangeType(), null, null);

        logger.info("变更申请已创建: {}", changeRecord.getChangeId());

        Map<String, Object> result = new HashMap<>();
        result.put("change_id", changeRecord.getChangeId());
        result.put("status", "pending");
        return result;
    }

    @Transactional
    public Map<String, Object> approveChange(String changeId, String approver,
                                             String comment, boolean approved) {
        ChangeRecord change = changeRecordRepository.findByChangeId(changeId)
                .orElseThrow(() -> new ContractException(404, "变更记录不存在: " + changeId));

        if (!"pending".equals(change.getChangeStatus())) {
            throw new ContractException(400, "变更已处理: " + change.getChangeStatus());
        }

        String newStatus = approved ? "approved" : "rejected";
        change.setChangeStatus(newStatus);
        change.setApprover(approver);
        change.setApprovalComment(comment);
        change.setChangeTime(LocalDateTime.now());
        changeRecordRepository.save(change);

        if (approved) {
            Contract contract = contractRepository.findByContractId(change.getContractId()).orElse(null);
            if (contract != null) {
                applyChangeToContract(contract, change);
                contract.setUpdatedAt(LocalDateTime.now());
                contractRepository.save(contract);
            }
        }

        String action = approved ? "approve" : "reject";
        historyService.recordHistory(change.getContractId(), "change", action,
                approver, "变更" + (approved ? "通过" : "拒绝") + ": " + comment,
                "pending", newStatus);

        logger.info("变更处理完成: {}, 状态: {}", changeId, newStatus);

        Map<String, Object> result = new HashMap<>();
        result.put("change_id", changeId);
        result.put("status", newStatus);
        return result;
    }

    private void applyChangeToContract(Contract contract, ChangeRecord change) {
        switch (change.getChangeType().toLowerCase()) {
            case "amount":
                if (change.getChangeAfter() != null) {
                    contract.setContractAmount(change.getChangeAfter());
                }
                break;
            case "term":
                break;
            default:
                break;
        }
    }

    public List<ChangeRecord> getChangeHistory(String contractId) {
        return changeRecordRepository.findByContractIdOrderByChangeTimeDesc(contractId);
    }

    public List<ChangeRecord> getPendingChanges() {
        return changeRecordRepository.findByChangeStatus("pending");
    }

    public ChangeRecord getChangeRecord(String changeId) {
        return changeRecordRepository.findByChangeId(changeId)
                .orElseThrow(() -> new ContractException(404, "变更记录不存在: " + changeId));
    }
}
