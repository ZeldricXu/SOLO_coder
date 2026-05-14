package com.healthtrack.service;

import com.healthtrack.entity.HealthHistory;
import com.healthtrack.repository.HealthHistoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class HistoryService {

    @Autowired
    private HealthHistoryRepository healthHistoryRepository;

    public void recordHistory(String userId, String dataType, String actionType, Double oldValue, Double newValue, String description) {
        HealthHistory history = new HealthHistory();
        history.setUserId(userId);
        history.setDataType(dataType);
        history.setActionType(actionType);
        history.setOldValue(oldValue);
        history.setNewValue(newValue);
        history.setDescription(description);
        healthHistoryRepository.save(history);
    }

    public List<HealthHistory> getUserHistory(String userId) {
        return healthHistoryRepository.findByUserIdOrderByRecordedAtDesc(userId);
    }

    public List<HealthHistory> getUserHistoryByType(String userId, String dataType) {
        return healthHistoryRepository.findByUserIdAndDataType(userId, dataType);
    }

    public List<HealthHistory> getUserHistoryByDateRange(String userId, LocalDate startDate, LocalDate endDate) {
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.plusDays(1).atStartOfDay();
        return healthHistoryRepository.findByUserIdAndRecordedAtBetween(userId, start, end);
    }

    public List<HealthHistory> getRecentHistory(String userId, int limit) {
        if (limit <= 0 || limit > 50) {
            limit = 50;
        }
        return healthHistoryRepository.findTop50ByUserIdOrderByRecordedAtDesc(userId).stream().limit(limit).toList();
    }
}
