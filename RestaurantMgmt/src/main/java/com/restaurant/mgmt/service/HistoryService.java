package com.restaurant.mgmt.service;

import com.restaurant.mgmt.exception.BusinessException;
import com.restaurant.mgmt.model.HistoryRecord;
import com.restaurant.mgmt.repository.HistoryRecordRepository;
import com.restaurant.mgmt.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class HistoryService {

    @Autowired
    private HistoryRecordRepository historyRecordRepository;

    @Transactional
    public HistoryRecord recordHistory(String recordType, String referenceId, String title, 
            String content, String operator, String action, String status) {
        HistoryRecord record = new HistoryRecord();
        record.setHistoryId(IdGenerator.generateHistoryId());
        record.setRecordType(recordType);
        record.setReferenceId(referenceId);
        record.setTitle(title);
        record.setContent(content);
        record.setOperator(operator);
        record.setAction(action);
        record.setStatus(status);
        record.setCreatedAt(LocalDateTime.now());
        
        return historyRecordRepository.save(record);
    }

    public HistoryRecord getHistoryById(String historyId) {
        return historyRecordRepository.findById(historyId)
                .orElseThrow(() -> new BusinessException("历史记录不存在"));
    }

    public List<HistoryRecord> getAllHistory() {
        return historyRecordRepository.findAll();
    }

    public List<HistoryRecord> getHistoryByType(String recordType) {
        return historyRecordRepository.findByRecordType(recordType);
    }

    public List<HistoryRecord> getHistoryByReferenceId(String referenceId) {
        return historyRecordRepository.findByReferenceId(referenceId);
    }

    public List<HistoryRecord> getHistoryByTimeRange(LocalDateTime startTime, LocalDateTime endTime) {
        return historyRecordRepository.findByCreatedAtBetween(startTime, endTime);
    }

    public List<HistoryRecord> getHistoryByTypeAndTimeRange(String recordType, 
            LocalDateTime startTime, LocalDateTime endTime) {
        return historyRecordRepository.findByRecordTypeAndCreatedAtBetween(recordType, startTime, endTime);
    }

    public List<HistoryRecord> getOrderHistory(String orderId) {
        return historyRecordRepository.findByReferenceId(orderId);
    }

    public List<HistoryRecord> getOrderHistoryByTimeRange(LocalDateTime startTime, LocalDateTime endTime) {
        return historyRecordRepository.findByRecordTypeAndCreatedAtBetween("order", startTime, endTime);
    }

    public List<HistoryRecord> getStockHistory(String stockId) {
        return historyRecordRepository.findByReferenceId(stockId);
    }

    public List<HistoryRecord> getStockHistoryByTimeRange(LocalDateTime startTime, LocalDateTime endTime) {
        return historyRecordRepository.findByRecordTypeAndCreatedAtBetween("stock", startTime, endTime);
    }

    public List<HistoryRecord> getTableHistory(String tableId) {
        return historyRecordRepository.findByReferenceId(tableId);
    }
}
