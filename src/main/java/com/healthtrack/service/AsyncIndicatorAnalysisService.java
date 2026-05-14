package com.healthtrack.service;

import com.healthtrack.entity.AnalysisTask;
import com.healthtrack.entity.HealthData;
import com.healthtrack.entity.HealthIndicator;
import com.healthtrack.repository.HealthDataRepository;
import com.healthtrack.repository.HealthIndicatorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AsyncIndicatorAnalysisService {

    private static final Logger logger = LoggerFactory.getLogger(AsyncIndicatorAnalysisService.class);
    
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 1000;

    private final AtomicInteger analysisCount = new AtomicInteger(0);
    private final AtomicInteger failedCount = new AtomicInteger(0);
    private final AtomicInteger retryCount = new AtomicInteger(0);

    @Autowired
    private HealthIndicatorRepository healthIndicatorRepository;

    @Autowired
    private HealthDataRepository healthDataRepository;

    @Autowired
    private ReminderService reminderService;

    @Autowired
    private AnalysisTaskQueueService analysisTaskQueueService;

    @Value("${healthtrack.analysis.use-redis-queue:true}")
    private boolean useRedisQueue;

    public CompletableFuture<HealthIndicator> scheduleAnalysis(String userId, String dataType, Double currentValue) {
        if (useRedisQueue) {
            logger.info("使用Redis队列调度分析任务: userId={}, dataType={}", userId, dataType);
            AnalysisTask task = new AnalysisTask(UUID.randomUUID().toString(), userId, dataType, currentValue);
            boolean enqueued = analysisTaskQueueService.enqueueTask(task);
            if (enqueued) {
                return CompletableFuture.completedFuture(null);
            } else {
                logger.warn("分析任务入队失败，降级到直接执行");
            }
        }
        return analyzeIndicatorAsync(userId, dataType, currentValue);
    }

    @Async("indicatorAnalysisExecutor")
    public CompletableFuture<HealthIndicator> analyzeIndicatorAsync(String userId, String dataType, Double currentValue) {
        return analyzeWithRetry(userId, dataType, currentValue, 1);
    }

    private CompletableFuture<HealthIndicator> analyzeWithRetry(String userId, String dataType, Double currentValue, int attempt) {
        try {
            logger.info("开始指标分析: userId={}, dataType={}, attempt={}", userId, dataType, attempt);
            
            Optional<HealthIndicator> existingIndicator = 
                    healthIndicatorRepository.findByUserIdAndIndicatorType(userId, dataType);
            
            HealthIndicator indicator;
            if (existingIndicator.isPresent()) {
                indicator = existingIndicator.get();
                updateExistingIndicator(indicator, currentValue, userId, dataType);
            } else {
                indicator = createNewIndicator(userId, dataType, currentValue);
            }
            
            HealthIndicator savedIndicator = healthIndicatorRepository.save(indicator);
            analysisCount.incrementAndGet();
            
            if ("abnormal".equals(savedIndicator.getStatus())) {
                reminderService.checkAndTriggerAbnormalityReminder(userId, dataType, currentValue);
            }
            
            logger.info("指标分析完成: userId={}, dataType={}, status={}", userId, dataType, savedIndicator.getStatus());
            return CompletableFuture.completedFuture(savedIndicator);
            
        } catch (Exception e) {
            if (attempt < MAX_RETRY_ATTEMPTS) {
                retryCount.incrementAndGet();
                logger.warn("指标分析失败，准备重试: userId={}, dataType={}, attempt={}, error={}", 
                        userId, dataType, attempt, e.getMessage());
                
                try {
                    Thread.sleep(RETRY_DELAY_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    failedCount.incrementAndGet();
                    CompletableFuture<HealthIndicator> future = new CompletableFuture<>();
                    future.completeExceptionally(ie);
                    return future;
                }
                
                return analyzeWithRetry(userId, dataType, currentValue, attempt + 1);
            } else {
                failedCount.incrementAndGet();
                logger.error("指标分析最终失败: userId={}, dataType={}, attempts={}, error={}", 
                        userId, dataType, attempt, e.getMessage(), e);
                CompletableFuture<HealthIndicator> future = new CompletableFuture<>();
                future.completeExceptionally(e);
                return future;
            }
        }
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
                return (value >= 60 && value <= 100) ? "normal" : "abnormal";
            case "weight":
                return (value >= 40 && value <= 120) ? "normal" : "abnormal";
            case "blood_pressure_systolic":
                return (value >= 90 && value <= 140) ? "normal" : "abnormal";
            case "blood_pressure_diastolic":
                return (value >= 60 && value <= 90) ? "normal" : "abnormal";
            case "temperature":
                return (value >= 36.5 && value <= 37.5) ? "normal" : "abnormal";
            case "steps":
                return (value >= 3000) ? "normal" : "abnormal";
            case "sleep_hours":
                return (value >= 6 && value <= 10) ? "normal" : "abnormal";
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

    public int getAnalysisCount() {
        return analysisCount.get();
    }

    public int getFailedCount() {
        return failedCount.get();
    }

    public int getRetryCount() {
        return retryCount.get();
    }

    public void resetCounters() {
        analysisCount.set(0);
        failedCount.set(0);
        retryCount.set(0);
    }
}
