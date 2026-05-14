package com.healthtrack.service;

import com.healthtrack.dto.HealthDataReportRequest;
import com.healthtrack.dto.HealthDataReportResponse;
import com.healthtrack.entity.HealthData;
import com.healthtrack.entity.HealthDataQueueTask;
import com.healthtrack.repository.HealthDataRepository;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class DataCollectionService {

    private static final Logger logger = LoggerFactory.getLogger(DataCollectionService.class);

    @Autowired
    private HealthDataRepository healthDataRepository;

    @Autowired
    private AsyncDataProcessingService asyncDataProcessingService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private HealthDataQueueService healthDataQueueService;

    @Value("${healthtrack.queue.use-redis:true}")
    private boolean useRedisQueue;

    private volatile boolean useAsyncProcessing = true;

    public HealthDataReportResponse reportHealthData(HealthDataReportRequest request) {
        logger.info("收到健康数据上报请求: userId={}, dataType={}, useRedisQueue={}", 
                request.getUserId(), request.getDataType(), useRedisQueue);
        
        validateRequest(request);
        
        String dataType = request.getDataType();
        Double dataValue = request.getDataValue();
        
        String quality = validateDataQuality(dataType, dataValue);
        
        HealthData healthData = createHealthDataRecord(request, quality);
        
        if (useRedisQueue) {
            logger.info("使用Redis队列模式: dataId={}", healthData.getDataId());
            HealthDataQueueTask task = HealthDataQueueTask.fromHealthData(healthData, request);
            boolean enqueued = healthDataQueueService.enqueueTask(task);
            
            if (enqueued) {
                logger.info("健康数据已入队: dataId={}, queueSize={}", 
                        healthData.getDataId(), healthDataQueueService.getQueueSize());
                return new HealthDataReportResponse(healthData.getDataId(), "queued");
            } else {
                logger.warn("Redis队列入队失败，降级到同步处理: dataId={}", healthData.getDataId());
                return processSynchronously(request, healthData, dataType, dataValue);
            }
        } else if (useAsyncProcessing) {
            logger.info("使用异步处理模式: dataId={}", healthData.getDataId());
            asyncDataProcessingService.processHealthDataAsync(healthData);
            return new HealthDataReportResponse(healthData.getDataId(), "processing");
        } else {
            return processSynchronously(request, healthData, dataType, dataValue);
        }
    }

    private HealthDataReportResponse processSynchronously(HealthDataReportRequest request, 
            HealthData healthData, String dataType, Double dataValue) {
        logger.info("使用同步处理模式: dataId={}", healthData.getDataId());
        HealthData savedData = healthDataRepository.save(healthData);
        
        historyService.recordHistory(request.getUserId(), dataType, "DATA_COLLECTED", 
                null, dataValue, "采集到" + dataType + "数据: " + dataValue);
        
        String indicatorStatus = "normal";
        return new HealthDataReportResponse(savedData.getDataId(), indicatorStatus);
    }

    public CompletableFuture<HealthDataReportResponse> reportHealthDataAsync(HealthDataReportRequest request) {
        logger.info("收到异步健康数据上报请求: userId={}, dataType={}, useRedisQueue={}", 
                request.getUserId(), request.getDataType(), useRedisQueue);
        
        validateRequest(request);
        
        String dataType = request.getDataType();
        Double dataValue = request.getDataValue();
        
        String quality = validateDataQuality(dataType, dataValue);
        
        HealthData healthData = createHealthDataRecord(request, quality);
        
        if (useRedisQueue) {
            return CompletableFuture.supplyAsync(() -> {
                HealthDataQueueTask task = HealthDataQueueTask.fromHealthData(healthData, request);
                boolean enqueued = healthDataQueueService.enqueueTask(task);
                if (enqueued) {
                    return new HealthDataReportResponse(healthData.getDataId(), "queued");
                } else {
                    return new HealthDataReportResponse(healthData.getDataId(), "failed");
                }
            });
        }
        
        return asyncDataProcessingService.processHealthDataAsync(healthData)
                .thenApply(savedData -> new HealthDataReportResponse(savedData.getDataId(), "completed"))
                .exceptionally(ex -> {
                    logger.error("异步处理失败: {}", ex.getMessage());
                    return new HealthDataReportResponse(healthData.getDataId(), "failed");
                });
    }

    private void validateRequest(HealthDataReportRequest request) {
        if (StringUtils.isBlank(request.getUserId())) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        if (StringUtils.isBlank(request.getDataType())) {
            throw new IllegalArgumentException("数据类型不能为空");
        }
        if (request.getDataValue() == null) {
            throw new IllegalArgumentException("数据值不能为空");
        }
    }

    public String validateDataQuality(String dataType, Double dataValue) {
        switch (dataType.toLowerCase()) {
            case "heart_rate":
                if (dataValue < 30 || dataValue > 220) {
                    return "abnormal";
                }
                break;
            case "weight":
                if (dataValue < 20 || dataValue > 300) {
                    return "abnormal";
                }
                break;
            case "blood_pressure_systolic":
                if (dataValue < 70 || dataValue > 250) {
                    return "abnormal";
                }
                break;
            case "blood_pressure_diastolic":
                if (dataValue < 40 || dataValue > 150) {
                    return "abnormal";
                }
                break;
            case "temperature":
                if (dataValue < 35 || dataValue > 42) {
                    return "abnormal";
                }
                break;
            case "steps":
                if (dataValue < 0 || dataValue > 100000) {
                    return "abnormal";
                }
                break;
            case "sleep_hours":
                if (dataValue < 0 || dataValue > 24) {
                    return "abnormal";
                }
                break;
            default:
                break;
        }
        return "good";
    }

    public HealthData createHealthDataRecord(HealthDataReportRequest request, String quality) {
        HealthData healthData = new HealthData();
        healthData.setDataId(generateDataId());
        healthData.setUserId(request.getUserId());
        healthData.setDataType(request.getDataType());
        healthData.setDataValue(request.getDataValue());
        healthData.setDataUnit(request.getDataUnit() != null ? request.getDataUnit() : getDefaultUnit(request.getDataType()));
        healthData.setDeviceId(request.getDeviceId());
        healthData.setCollectedAt(LocalDateTime.now());
        healthData.setQuality(quality);
        return healthData;
    }

    private String generateDataId() {
        return "data_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public String getDefaultUnit(String dataType) {
        switch (dataType.toLowerCase()) {
            case "heart_rate":
                return "bpm";
            case "weight":
                return "kg";
            case "blood_pressure_systolic":
            case "blood_pressure_diastolic":
                return "mmHg";
            case "temperature":
                return "°C";
            case "steps":
                return "steps";
            case "sleep_hours":
                return "hours";
            default:
                return "";
        }
    }

    public void setUseAsyncProcessing(boolean useAsyncProcessing) {
        this.useAsyncProcessing = useAsyncProcessing;
    }

    public boolean isUseAsyncProcessing() {
        return useAsyncProcessing;
    }
}
