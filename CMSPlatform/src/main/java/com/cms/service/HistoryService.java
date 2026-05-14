package com.cms.service;

import com.cms.entity.HistoryRecord;
import com.cms.exception.BusinessException;
import com.cms.repository.HistoryRecordRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class HistoryService {

    @Autowired
    private HistoryRecordRepository historyRecordRepository;

    public List<HistoryRecord> getContentHistory(String contentId) {
        return historyRecordRepository.findByContentIdOrderByOperationTimeDesc(contentId);
    }

    public List<HistoryRecord> getOperatorHistory(String operatorId) {
        return historyRecordRepository.findByOperatorIdOrderByOperationTimeDesc(operatorId);
    }

    public List<HistoryRecord> getHistoryByOperationType(String operationType) {
        return historyRecordRepository.findByOperationTypeOrderByOperationTimeDesc(operationType);
    }

    public List<HistoryRecord> getContentHistoryByOperationType(String contentId, String operationType) {
        return historyRecordRepository.findByContentIdAndOperationType(contentId, operationType);
    }

    public HistoryRecord getHistoryById(String historyId) {
        return historyRecordRepository.findById(historyId)
                .orElseThrow(() -> new BusinessException(404, "历史记录不存在"));
    }
}
