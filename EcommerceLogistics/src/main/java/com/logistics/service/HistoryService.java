package com.logistics.service;

import com.logistics.entity.LogisticsHistory;
import com.logistics.repository.LogisticsHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class HistoryService {

    private final LogisticsHistoryRepository historyRepository;

    @Transactional
    public LogisticsHistory recordHistory(LogisticsHistory history) {
        return historyRepository.save(history);
    }

    public List<LogisticsHistory> getHistoryByLogisticsId(String logisticsId) {
        return historyRepository.findByLogisticsIdOrderByHistoryTimeAsc(logisticsId);
    }

    public List<LogisticsHistory> getHistoryByType(String historyType) {
        return historyRepository.findByHistoryType(historyType);
    }

    public List<LogisticsHistory> getAllHistory() {
        return historyRepository.findAll();
    }
}
