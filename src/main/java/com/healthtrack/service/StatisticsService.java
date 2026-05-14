package com.healthtrack.service;

import com.healthtrack.entity.HealthData;
import com.healthtrack.entity.HealthGoal;
import com.healthtrack.entity.HealthIndicator;
import com.healthtrack.entity.HealthStatistics;
import com.healthtrack.repository.HealthDataRepository;
import com.healthtrack.repository.HealthGoalRepository;
import com.healthtrack.repository.HealthIndicatorRepository;
import com.healthtrack.repository.HealthStatisticsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class StatisticsService {

    @Autowired
    private HealthStatisticsRepository healthStatisticsRepository;

    @Autowired
    private HealthDataRepository healthDataRepository;

    @Autowired
    private HealthIndicatorRepository healthIndicatorRepository;

    @Autowired
    private HealthGoalRepository healthGoalRepository;

    public void updateStatistics(String userId, String dataType, boolean isNormal) {
        LocalDate today = LocalDate.now();
        
        HealthStatistics statistics = healthStatisticsRepository
                .findByUserIdAndStatDate(userId, today)
                .orElseGet(() -> createNewStatistics(userId, today));
        
        statistics.setTotalRecords(statistics.getTotalRecords() + 1);
        
        if (isNormal) {
            statistics.setNormalCount(statistics.getNormalCount() + 1);
        } else {
            statistics.setAbnormalCount(statistics.getAbnormalCount() + 1);
        }
        
        updateAverages(statistics, userId);
        updateGoalProgress(statistics, userId);
        
        statistics.setUpdatedAt(LocalDateTime.now());
        healthStatisticsRepository.save(statistics);
    }

    private HealthStatistics createNewStatistics(String userId, LocalDate date) {
        HealthStatistics statistics = new HealthStatistics();
        statistics.setStatId("stat_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        statistics.setUserId(userId);
        statistics.setStatDate(date);
        return statistics;
    }

    private void updateAverages(HealthStatistics statistics, String userId) {
        LocalDate today = LocalDate.now();
        LocalDateTime startOfDay = today.atStartOfDay();
        LocalDateTime endOfDay = today.plusDays(1).atStartOfDay();
        
        Double avgHeartRate = healthDataRepository.findAverageValueByUserIdAndDataTypeAndTimeRange(
                userId, "heart_rate", startOfDay, endOfDay);
        if (avgHeartRate != null) {
            statistics.setAvgHeartRate(Math.round(avgHeartRate * 100.0) / 100.0);
        }
        
        Double avgWeight = healthDataRepository.findAverageValueByUserIdAndDataTypeAndTimeRange(
                userId, "weight", startOfDay, endOfDay);
        if (avgWeight != null) {
            statistics.setAvgWeight(Math.round(avgWeight * 100.0) / 100.0);
        }
    }

    private void updateGoalProgress(HealthStatistics statistics, String userId) {
        List<HealthGoal> goals = healthGoalRepository.findByUserId(userId);
        if (!goals.isEmpty()) {
            int totalProgress = goals.stream()
                    .filter(g -> g.getProgress() != null)
                    .mapToInt(HealthGoal::getProgress)
                    .sum();
            statistics.setGoalProgress(totalProgress / goals.size());
        }
    }

    public HealthStatistics getTodayStatistics(String userId) {
        LocalDate today = LocalDate.now();
        Optional<HealthStatistics> stats = healthStatisticsRepository.findByUserIdAndStatDate(userId, today);
        return stats.orElseGet(() -> createNewStatistics(userId, today));
    }

    public List<HealthStatistics> getStatisticsByDateRange(String userId, LocalDate start, LocalDate end) {
        return healthStatisticsRepository.findByUserIdAndStatDateBetween(userId, start, end);
    }

    public HealthStatistics generateDailySummary(String userId, LocalDate date) {
        LocalDateTime startOfDay = date.atStartOfDay();
        LocalDateTime endOfDay = date.plusDays(1).atStartOfDay();
        
        HealthStatistics statistics = healthStatisticsRepository
                .findByUserIdAndStatDate(userId, date)
                .orElseGet(() -> {
                    HealthStatistics newStats = new HealthStatistics();
                    newStats.setStatId("stat_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
                    newStats.setUserId(userId);
                    newStats.setStatDate(date);
                    return newStats;
                });
        
        long totalRecords = healthDataRepository.countByUserIdAndCollectedAtBetween(userId, startOfDay, endOfDay);
        statistics.setTotalRecords((int) totalRecords);
        
        long normalCount = healthDataRepository.countByUserIdAndQuality(userId, "good");
        statistics.setNormalCount((int) normalCount);
        statistics.setAbnormalCount((int) (totalRecords - normalCount));
        
        updateAverages(statistics, userId);
        updateGoalProgress(statistics, userId);
        
        statistics.setUpdatedAt(LocalDateTime.now());
        return healthStatisticsRepository.save(statistics);
    }
}
