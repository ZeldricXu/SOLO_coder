package com.contractmgmt.service;

import cn.hutool.core.util.IdUtil;
import com.contractmgmt.entity.ContractHistory;
import com.contractmgmt.repository.ContractHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class HistoryService {

    private static final Logger logger = LoggerFactory.getLogger(HistoryService.class);

    private final ContractHistoryRepository contractHistoryRepository;

    public HistoryService(ContractHistoryRepository contractHistoryRepository) {
        this.contractHistoryRepository = contractHistoryRepository;
    }

    @Transactional
    public void recordHistory(String contractId, String historyType, String action,
                               String operator, String detail, String oldValue, String newValue) {
        ContractHistory history = new ContractHistory();
        history.setHistoryId("history_" + IdUtil.getSnowflakeNextIdStr());
        history.setContractId(contractId);
        history.setHistoryType(historyType);
        history.setAction(action);
        history.setOperator(operator != null ? operator : "system");
        history.setDetail(detail);
        history.setOldValue(oldValue);
        history.setNewValue(newValue);
        history.setActionTime(LocalDateTime.now());

        contractHistoryRepository.save(history);
        logger.debug("历史记录已保存: 合同={}, 操作={}", contractId, action);
    }

    public List<ContractHistory> getContractHistory(String contractId) {
        return contractHistoryRepository.findByContractIdOrderByActionTimeDesc(contractId);
    }

    public List<ContractHistory> getHistoryByType(String contractId, String historyType) {
        return contractHistoryRepository.findByContractIdAndHistoryTypeOrderByActionTimeDesc(
                contractId, historyType);
    }

    public List<ContractHistory> getHistoryByOperator(String operator) {
        return contractHistoryRepository.findByOperator(operator);
    }

    public List<ContractHistory> getHistoryByHistoryType(String historyType) {
        return contractHistoryRepository.findByHistoryType(historyType);
    }
}
