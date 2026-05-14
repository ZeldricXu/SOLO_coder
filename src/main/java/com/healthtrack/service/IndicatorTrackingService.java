package com.healthtrack.service;

import com.healthtrack.entity.HealthData;
import com.healthtrack.entity.HealthIndicator;
import com.healthtrack.repository.HealthDataRepository;
import com.healthtrack.repository.HealthIndicatorRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class IndicatorTrackingService {

    @Autowired
    private HealthIndicatorRepository healthIndicatorRepository;

    @Autowired
    private HealthDataRepository healthDataRepository;

    @Autowired
    private ReminderService reminderService;

    public String updateIndicator(String userId, String dataType, Double currentValue) {
        Optional<HealthIndicator> existingIndicator = 
                healthIndicatorRepository.findByUserIdAndIndicatorType(userId, dataType);
        
        HealthIndicator indicator;
        if (existingIndicator.isPresent()) {
            indicator = existingIndicator.get();
            updateExistingIndicator(indicator, currentValue, userId, dataType);
        } else {
            indicator = createNewIndicator(userId, dataType, currentValue);
        }
        
        healthIndicatorRepository.save(indicator);
        
        if ("abnormal".equals(indicator.getStatus())) {
            reminderService.checkAndTriggerAbnormalityReminder(userId, dataType, currentValue);
        }
        
        return indicator.getStatus();
    }

    private void updateExistingIndicator(HealthIndicator indicator, Double currentValue, String userId, String dataType) {
        Double oldValue = indicator.getCurrentValue();
        indicator.setCurrentValue(currentValue);
        
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime sevenDaysAgo = now.minusDays(7);
        
        List<HealthData> recentData = healthDataRepository.findByUserIdAndDataTypeAndCollectedAtBetween(
                userId, dataType, sevenDaysAgo, now);
        
        if (!recentData.isEmpty()) {
            double sum = recentData.stream().mapToDouble(HealthData::getDataValue).sum();
            indicator.setAverageValue(Math.round(sum / recentData.size() * 100.0) / 100.0);
            
            double max = recentData.stream().mapToDouble(HealthData::getDataValue).max().orElse(currentValue);
            double min = recentData.stream().mapToDouble(HealthData::getDataValue).min().orElse(currentValue);
            indicator.setMaxValue(max);
            indicator.setMinValue(min);
        }
        
        indicator.setTrend(analyzeTrend(oldValue, currentValue));
        indicator.setStatus(determineStatus(dataType, currentValue));
        indicator.setUpdatedAt(LocalDateTime.now());
    }

    private HealthIndicator createNewIndicator(String userId, String dataType, Double currentValue) {
        HealthIndicator indicator = new HealthIndicator();
        indicator.setIndicatorId("indicator_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        indicator.setUserId(userId);
        indicator.setIndicatorType(dataType);
        indicator.setCurrentValue(currentValue);
        indicator.setAverageValue(currentValue);
        indicator.setTargetValue(getDefaultTarget(dataType));
        indicator.setMaxValue(currentValue);
        indicator.setMinValue(currentValue);
        indicator.setTrend("stable");
        indicator.setStatus(determineStatus(dataType, currentValue));
        return indicator;
    }

    private String analyzeTrend(Double oldValue, Double newValue) {
        if (oldValue == null) {
            return "stable";
        }
        double change = newValue - oldValue;
        double percentage = Math.abs(change / oldValue) * 100;
        
        if (percentage < 3) {
            return "stable";
        } else if (change > 0) {
            return "rising";
        } else {
            return "falling";
        }
    }

    private String determineStatus(String dataType, Double value) {
        switch (dataType.toLowerCase()) {
            case "heart_rate":
                if (value >= 60 && value <= 100) {
                    return "normal";
                }
                return "abnormal";
            case "weight":
                if (value >= 40 && value <= 120) {
                    return "normal";
                }
                return "abnormal";
            case "blood_pressure_systolic":
                if (value >= 90 && value <= 140) {
                    return "normal";
                }
                return "abnormal";
            case "blood_pressure_diastolic":
                if (value >= 60 && value <= 90) {
                    return "normal";
                }
                return "abnormal";
            case "temperature":
                if (value >= 36.5 && value <= 37.5) {
                    return "normal";
                }
                return "abnormal";
            case "steps":
                if (value >= 3000) {
                    return "normal";
                }
                return "abnormal";
            case "sleep_hours":
                if (value >= 6 && value <= 10) {
                    return "normal";
                }
                return "abnormal";
            default:
                return "normal";
        }
    }

    private Double getDefaultTarget(String dataType) {
        switch (dataType.toLowerCase()) {
            case "heart_rate":
                return 75.0;
            case "weight":
                return 65.0;
            case "blood_pressure_systolic":
                return 120.0;
            case "blood_pressure_diastolic":
                return 80.0;
            case "temperature":
                return 37.0;
            case "steps":
                return 8000.0;
            case "sleep_hours":
                return 8.0;
            default:
                return 0.0;
        }
    }

    public List<HealthIndicator> getUserIndicators(String userId) {
        return healthIndicatorRepository.findByUserId(userId);
    }

    public Optional<HealthIndicator> getUserIndicatorByType(String userId, String indicatorType) {
        return healthIndicatorRepository.findByUserIdAndIndicatorType(userId, indicatorType);
    }
}
