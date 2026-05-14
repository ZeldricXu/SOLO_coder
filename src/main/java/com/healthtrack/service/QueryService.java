package com.healthtrack.service;

import com.healthtrack.entity.HealthData;
import com.healthtrack.entity.HealthHistory;
import com.healthtrack.repository.HealthDataRepository;
import com.healthtrack.repository.HealthHistoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class QueryService {

    @Autowired
    private HealthDataRepository healthDataRepository;

    @Autowired
    private HealthHistoryRepository healthHistoryRepository;

    public List<HealthData> queryHealthDataByUser(String userId) {
        return healthDataRepository.findByUserId(userId);
    }

    public List<HealthData> queryHealthDataByType(String userId, String dataType) {
        return healthDataRepository.findByUserIdAndDataType(userId, dataType);
    }

    public List<HealthData> queryHealthDataByDateRange(String userId, LocalDate startDate, LocalDate endDate) {
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.plusDays(1).atStartOfDay();
        return healthDataRepository.findByUserIdAndCollectedAtBetween(userId, start, end);
    }

    public List<HealthData> queryHealthDataByTypeAndDateRange(String userId, String dataType, LocalDate startDate, LocalDate endDate) {
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.plusDays(1).atStartOfDay();
        return healthDataRepository.findByUserIdAndDataTypeAndCollectedAtBetween(userId, dataType, start, end);
    }

    public Optional<HealthData> getLatestHealthData(String userId, String dataType) {
        return healthDataRepository.findFirstByUserIdAndDataTypeOrderByCollectedAtDesc(userId, dataType);
    }

    public Double getAverageValue(String userId, String dataType, LocalDate startDate, LocalDate endDate) {
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.plusDays(1).atStartOfDay();
        return healthDataRepository.findAverageValueByUserIdAndDataTypeAndTimeRange(userId, dataType, start, end);
    }

    public Double getMaxValue(String userId, String dataType, LocalDate startDate, LocalDate endDate) {
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.plusDays(1).atStartOfDay();
        return healthDataRepository.findMaxValueByUserIdAndDataTypeAndTimeRange(userId, dataType, start, end);
    }

    public Double getMinValue(String userId, String dataType, LocalDate startDate, LocalDate endDate) {
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.plusDays(1).atStartOfDay();
        return healthDataRepository.findMinValueByUserIdAndDataTypeAndTimeRange(userId, dataType, start, end);
    }
}
