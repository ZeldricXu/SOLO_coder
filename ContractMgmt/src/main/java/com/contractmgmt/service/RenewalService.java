package com.contractmgmt.service;

import cn.hutool.core.util.IdUtil;
import com.contractmgmt.dto.RenewalRequest;
import com.contractmgmt.entity.Contract;
import com.contractmgmt.entity.RenewalRecord;
import com.contractmgmt.exception.ContractException;
import com.contractmgmt.repository.ContractRepository;
import com.contractmgmt.repository.RenewalRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RenewalService {

    private static final Logger logger = LoggerFactory.getLogger(RenewalService.class);

    private final RenewalRecordRepository renewalRecordRepository;
    private final ContractRepository contractRepository;
    private final HistoryService historyService;

    public RenewalService(
            RenewalRecordRepository renewalRecordRepository,
            ContractRepository contractRepository,
            HistoryService historyService) {
        this.renewalRecordRepository = renewalRecordRepository;
        this.contractRepository = contractRepository;
        this.historyService = historyService;
    }

    @Transactional
    public Map<String, Object> createRenewalRequest(RenewalRequest request) {
        Contract originalContract = contractRepository.findByContractId(request.getOriginalContractId())
                .orElseThrow(() -> new ContractException(404, "原合同不存在: " + request.getOriginalContractId()));

        if (request.getRenewalStart() != null && request.getRenewalEnd() != null) {
            if (request.getRenewalStart().isAfter(request.getRenewalEnd())) {
                throw new ContractException(400, "续签期限无效，开始日期不能晚于结束日期");
            }
        }

        String newContractId = generateNewContractId();

        RenewalRecord renewalRecord = new RenewalRecord();
        renewalRecord.setRenewalId("renewal_" + IdUtil.getSnowflakeNextIdStr());
        renewalRecord.setContractId(newContractId);
        renewalRecord.setOriginalContractId(request.getOriginalContractId());
        renewalRecord.setRenewalAmount(request.getRenewalAmount());
        renewalRecord.setRenewalStart(request.getRenewalStart());
        renewalRecord.setRenewalEnd(request.getRenewalEnd());
        renewalRecord.setRenewalReason(request.getRenewalReason());
        renewalRecord.setRenewalStatus("pending");
        renewalRecord.setRenewalTime(LocalDateTime.now());
        renewalRecordRepository.save(renewalRecord);

        Contract newContract = new Contract();
        newContract.setContractId(newContractId);
        newContract.setContractName(originalContract.getContractName() + "(续签)");
        newContract.setContractType(originalContract.getContractType());
        newContract.setContractAmount(request.getRenewalAmount());
        newContract.setContractStart(request.getRenewalStart());
        newContract.setContractEnd(request.getRenewalEnd());
        newContract.setPartyA(originalContract.getPartyA());
        newContract.setPartyB(originalContract.getPartyB());
        newContract.setContractStatus("pending_approval");
        newContract.setExecutionProgress(0);
        newContract.setExecutionStatus("pending");
        contractRepository.save(newContract);

        String operator = request.getOperator() != null ? request.getOperator() : "system";
        historyService.recordHistory(request.getOriginalContractId(), "renewal", "create",
                operator, "创建续签申请: 新合同 " + newContractId, null, null);

        logger.info("续签申请已创建: 原合同={}, 新合同={}", request.getOriginalContractId(), newContractId);

        Map<String, Object> result = new HashMap<>();
        result.put("renewal_id", renewalRecord.getRenewalId());
        result.put("new_contract_id", newContractId);
        result.put("status", "pending");
        return result;
    }

    @Transactional
    public Map<String, Object> approveRenewal(String renewalId, String approver,
                                               String comment, boolean approved) {
        RenewalRecord renewal = renewalRecordRepository.findByRenewalId(renewalId)
                .orElseThrow(() -> new ContractException(404, "续签记录不存在: " + renewalId));

        if (!"pending".equals(renewal.getRenewalStatus())) {
            throw new ContractException(400, "续签已处理: " + renewal.getRenewalStatus());
        }

        String newStatus = approved ? "approved" : "rejected";
        renewal.setRenewalStatus(newStatus);
        renewal.setApprover(approver);
        renewal.setApprovalComment(comment);
        renewal.setRenewalTime(LocalDateTime.now());
        renewalRecordRepository.save(renewal);

        if (approved) {
            Contract contract = contractRepository.findByContractId(renewal.getContractId()).orElse(null);
            if (contract != null) {
                contract.setContractStatus("approved");
                contract.setEffectiveTime(LocalDateTime.now());
                contract.setExecutionStatus("in_progress");
                contract.setUpdatedAt(LocalDateTime.now());
                contractRepository.save(contract);
            }
        } else {
            Contract contract = contractRepository.findByContractId(renewal.getContractId()).orElse(null);
            if (contract != null) {
                contract.setContractStatus("rejected");
                contract.setUpdatedAt(LocalDateTime.now());
                contractRepository.save(contract);
            }
        }

        String action = approved ? "approve" : "reject";
        historyService.recordHistory(renewal.getOriginalContractId(), "renewal", action,
                approver, "续签" + (approved ? "通过" : "拒绝") + ": " + comment,
                "pending", newStatus);

        logger.info("续签处理完成: {}, 状态: {}", renewalId, newStatus);

        Map<String, Object> result = new HashMap<>();
        result.put("renewal_id", renewalId);
        result.put("status", newStatus);
        return result;
    }

    private String generateNewContractId() {
        String dateStr = java.time.LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String uuid = IdUtil.getSnowflakeNextIdStr();
        return "contract_" + dateStr + "_" + uuid.substring(uuid.length() - 6);
    }

    public List<RenewalRecord> getRenewalHistory(String contractId) {
        return renewalRecordRepository.findByOriginalContractIdOrderByRenewalTimeDesc(contractId);
    }

    public List<RenewalRecord> getPendingRenewals() {
        return renewalRecordRepository.findByRenewalStatus("pending");
    }

    public RenewalRecord getRenewalRecord(String renewalId) {
        return renewalRecordRepository.findByRenewalId(renewalId)
                .orElseThrow(() -> new ContractException(404, "续签记录不存在: " + renewalId));
    }
}
