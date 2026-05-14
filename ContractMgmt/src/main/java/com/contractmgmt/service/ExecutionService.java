package com.contractmgmt.service;

import cn.hutool.core.util.IdUtil;
import com.contractmgmt.dto.ExecutionRequest;
import com.contractmgmt.entity.Contract;
import com.contractmgmt.entity.ExecutionRecord;
import com.contractmgmt.exception.ContractException;
import com.contractmgmt.repository.ContractRepository;
import com.contractmgmt.repository.ExecutionRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ExecutionService {

    private static final Logger logger = LoggerFactory.getLogger(ExecutionService.class);

    private final ExecutionRecordRepository executionRecordRepository;
    private final ContractRepository contractRepository;
    private final HistoryService historyService;

    public ExecutionService(
            ExecutionRecordRepository executionRecordRepository,
            ContractRepository contractRepository,
            HistoryService historyService) {
        this.executionRecordRepository = executionRecordRepository;
        this.contractRepository = contractRepository;
        this.historyService = historyService;
    }

    public void initializeExecutionTracking(String contractId) {
        logger.info("初始化合同执行追踪: {}", contractId);
    }

    @Transactional
    public Map<String, Object> recordExecution(ExecutionRequest request) {
        Contract contract = contractRepository.findByContractId(request.getContractId())
                .orElseThrow(() -> new ContractException(404, "合同不存在: " + request.getContractId()));

        if (!"approved".equals(contract.getContractStatus())) {
            throw new ContractException(400, "合同状态不允许执行记录: " + contract.getContractStatus());
        }

        if (request.getExecutionProgress() < 0 || request.getExecutionProgress() > 100) {
            throw new ContractException(400, "执行进度必须在0-100之间");
        }

        ExecutionRecord record = new ExecutionRecord();
        record.setExecutionId("execution_" + IdUtil.getSnowflakeNextIdStr());
        record.setContractId(request.getContractId());
        record.setExecutionType(request.getExecutionType());
        record.setExecutionAmount(request.getExecutionAmount());
        record.setExecutionProgress(request.getExecutionProgress());
        record.setExecutionDescription(request.getExecutionDescription());
        record.setExecutionTime(LocalDateTime.now());
        executionRecordRepository.save(record);

        Integer oldProgress = contract.getExecutionProgress();
        contract.setExecutionProgress(request.getExecutionProgress());

        String executionStatus;
        if (request.getExecutionProgress() >= 100) {
            executionStatus = "completed";
        } else if (request.getExecutionProgress() > 0) {
            executionStatus = "in_progress";
        } else {
            executionStatus = "pending";
        }
        contract.setExecutionStatus(executionStatus);
        contract.setUpdatedAt(LocalDateTime.now());
        contractRepository.save(contract);

        String operator = request.getOperator() != null ? request.getOperator() : "system";
        historyService.recordHistory(request.getContractId(), "execution", "record",
                operator,
                "执行记录: " + request.getExecutionType() + ", 进度: " + request.getExecutionProgress() + "%",
                oldProgress + "%", request.getExecutionProgress() + "%");

        logger.info("合同执行记录已保存: {}, 进度: {}%", request.getContractId(), request.getExecutionProgress());

        Map<String, Object> result = new HashMap<>();
        result.put("execution_id", record.getExecutionId());
        result.put("progress", request.getExecutionProgress());
        result.put("execution_status", executionStatus);
        return result;
    }

    public List<ExecutionRecord> getExecutionHistory(String contractId) {
        return executionRecordRepository.findByContractIdOrderByExecutionTimeDesc(contractId);
    }

    public Integer getCurrentProgress(String contractId) {
        Integer maxProgress = executionRecordRepository.findMaxProgressByContractId(contractId);
        return maxProgress != null ? maxProgress : 0;
    }
}
