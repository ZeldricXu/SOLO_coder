package com.healthtrack.service;

import com.healthtrack.entity.HealthData;
import com.healthtrack.repository.HealthDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AsyncDataProcessingService {

    private static final Logger logger = LoggerFactory.getLogger(AsyncDataProcessingService.class);
    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final AtomicInteger failedCount = new AtomicInteger(0);

    @Autowired
    private HealthDataRepository healthDataRepository;

    @Autowired
    private IndicatorTrackingService indicatorTrackingService;

    @Autowired
    private GoalManagementService goalManagementService;

    @Autowired
    private AdvicePushService advicePushService;

    @Autowired
    private StatisticsService statisticsService;

    @Autowired
    private HistoryService historyService;

    @Async("dataCollectionExecutor")
    public CompletableFuture<HealthData> processHealthDataAsync(HealthData healthData) {
        try {
            logger.info("开始异步处理健康数据: dataId={}, userId={}", healthData.getDataId(), healthData.getUserId());
            
            HealthData savedData = healthDataRepository.save(healthData);
            processedCount.incrementAndGet();
            
            historyService.recordHistory(healthData.getUserId(), healthData.getDataType(), "DATA_PROCESSED",
                    null, healthData.getDataValue(), "异步处理健康数据: " + healthData.getDataType());
            
            indicatorTrackingService.updateIndicator(
                    healthData.getUserId(), healthData.getDataType(), healthData.getDataValue());
            
            goalManagementService.checkGoals(healthData.getUserId(), healthData.getDataType(), healthData.getDataValue());
            
            advicePushService.generateAdviceIfNeeded(healthData.getUserId(), healthData.getDataType());
            
            statisticsService.updateStatistics(healthData.getUserId(), healthData.getDataType(), 
                    "good".equals(healthData.getQuality()));
            
            logger.info("异步处理健康数据完成: dataId={}", healthData.getDataId());
            return CompletableFuture.completedFuture(savedData);
            
        } catch (Exception e) {
            failedCount.incrementAndGet();
            logger.error("异步处理健康数据失败: dataId={}, error={}", healthData.getDataId(), e.getMessage(), e);
            CompletableFuture<HealthData> future = new CompletableFuture<>();
            future.completeExceptionally(e);
            return future;
        }
    }

    @Async("dataCollectionExecutor")
    public CompletableFuture<Boolean> saveDataOnlyAsync(HealthData healthData) {
        try {
            healthDataRepository.save(healthData);
            processedCount.incrementAndGet();
            return CompletableFuture.completedFuture(true);
        } catch (Exception e) {
            failedCount.incrementAndGet();
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            future.completeExceptionally(e);
            return future;
        }
    }

    public int getProcessedCount() {
        return processedCount.get();
    }

    public int getFailedCount() {
        return failedCount.get();
    }

    public void resetCounters() {
        processedCount.set(0);
        failedCount.set(0);
    }
}
