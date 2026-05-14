package com.crm.service;

import com.crm.entity.History;
import com.crm.repository.HistoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class HistoryService {

    @Autowired
    private HistoryRepository historyRepository;

    @Transactional
    public void recordHistory(String customerId, String historyType, String relatedId, String action, String detail, String operator) {
        History history = new History();
        history.setCustomerId(customerId);
        history.setHistoryType(historyType);
        history.setRelatedId(relatedId);
        history.setAction(action);
        history.setDetail(detail);
        history.setOperator(operator);
        historyRepository.save(history);
    }

    public List<History> getCustomerHistory(String customerId) {
        return historyRepository.findByCustomerId(customerId);
    }

    public List<History> getHistoryByType(String historyType) {
        return historyRepository.findByHistoryType(historyType);
    }

    public List<History> getHistoryByRelatedId(String relatedId) {
        return historyRepository.findByRelatedId(relatedId);
    }
}
