package com.cms.service;

import com.cms.entity.ContentStatistics;
import com.cms.repository.ContentStatisticsRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class StatisticsWorkerService {

    private static final Logger logger = LoggerFactory.getLogger(StatisticsWorkerService.class);

    @Autowired
    private StatisticsQueueService statisticsQueueService;

    @Autowired
    private ContentStatisticsRepository contentStatisticsRepository;

    private final ObjectMapper objectMapper;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    public StatisticsWorkerService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @Scheduled(fixedDelay = 1000)
    public void processViewQueue() {
        processQueue("view");
    }

    @Scheduled(fixedDelay = 2000)
    public void processLikeQueue() {
        processQueue("like");
    }

    @Scheduled(fixedDelay = 3000)
    public void processShareQueue() {
        processQueue("share");
    }

    @Async
    public void processQueueAsync(String queueType) {
        processQueue(queueType);
    }

    @Transactional
    public void processQueue(String queueType) {
        if (!isRunning.compareAndSet(false, true)) {
            return;
        }

        try {
            int processedCount = 0;
            int maxBatchSize = 100;

            while (processedCount < maxBatchSize) {
                String taskJson = getTaskFromQueue(queueType);
                if (taskJson == null) {
                    break;
                }

                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> taskData = objectMapper.readValue(taskJson, Map.class);
                    
                    String taskId = (String) taskData.get("taskId");
                    String operationType = (String) taskData.get("operationType");
                    String contentId = (String) taskData.get("contentId");

                    statisticsQueueService.markTaskProcessing(taskId);

                    executeStatisticsUpdate(operationType, contentId);

                    statisticsQueueService.markTaskCompleted(taskId);

                    processedCount++;

                    if (processedCount % 10 == 0) {
                        logger.debug("已处理{}个{}统计任务", processedCount, operationType);
                    }
                } catch (Exception e) {
                    logger.error("处理统计任务失败: queueType={}", queueType, e);
                }
            }

            if (processedCount > 0) {
                logger.info("完成处理{}个{}统计任务", processedCount, queueType);
            }
        } finally {
            isRunning.set(false);
        }
    }

    private String getTaskFromQueue(String queueType) {
        switch (queueType.toLowerCase()) {
            case "view":
                return statisticsQueueService.dequeueViewTask();
            case "like":
                return statisticsQueueService.dequeueLikeTask();
            case "share":
                return statisticsQueueService.dequeueShareTask();
            default:
                return null;
        }
    }

    @Transactional
    public void executeStatisticsUpdate(String operationType, String contentId) {
        ContentStatistics statistics = contentStatisticsRepository.findByContentId(contentId)
            .orElseGet(() -> {
                ContentStatistics newStat = new ContentStatistics();
                newStat.setStatId("stat_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
                newStat.setContentId(contentId);
                newStat.setViewCount(0L);
                newStat.setLikeCount(0L);
                newStat.setCommentCount(0L);
                newStat.setShareCount(0L);
                return newStat;
            });

        switch (operationType.toLowerCase()) {
            case "view":
                statistics.setViewCount(statistics.getViewCount() + 1);
                break;
            case "like":
                statistics.setLikeCount(statistics.getLikeCount() + 1);
                break;
            case "share":
                statistics.setShareCount(statistics.getShareCount() + 1);
                break;
            default:
                logger.warn("未知的统计操作类型: {}", operationType);
                return;
        }

        contentStatisticsRepository.save(statistics);
    }

    @Transactional
    public void updateViewCount(String contentId) {
        ContentStatistics statistics = getOrCreateStatistics(contentId);
        statistics.setViewCount(statistics.getViewCount() + 1);
        contentStatisticsRepository.save(statistics);
        logger.debug("更新阅读计数: contentId={}, count={}", contentId, statistics.getViewCount());
    }

    @Transactional
    public void updateLikeCount(String contentId) {
        ContentStatistics statistics = getOrCreateStatistics(contentId);
        statistics.setLikeCount(statistics.getLikeCount() + 1);
        contentStatisticsRepository.save(statistics);
        logger.debug("更新点赞计数: contentId={}, count={}", contentId, statistics.getLikeCount());
    }

    @Transactional
    public void updateShareCount(String contentId) {
        ContentStatistics statistics = getOrCreateStatistics(contentId);
        statistics.setShareCount(statistics.getShareCount() + 1);
        contentStatisticsRepository.save(statistics);
        logger.debug("更新分享计数: contentId={}, count={}", contentId, statistics.getShareCount());
    }

    @Transactional
    public void updateCommentCount(String contentId) {
        ContentStatistics statistics = getOrCreateStatistics(contentId);
        statistics.setCommentCount(statistics.getCommentCount() + 1);
        contentStatisticsRepository.save(statistics);
        logger.debug("更新评论计数: contentId={}, count={}", contentId, statistics.getCommentCount());
    }

    private ContentStatistics getOrCreateStatistics(String contentId) {
        Optional<ContentStatistics> existingOpt = contentStatisticsRepository.findByContentId(contentId);
        
        if (existingOpt.isPresent()) {
            return existingOpt.get();
        }

        ContentStatistics newStat = new ContentStatistics();
        newStat.setStatId("stat_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        newStat.setContentId(contentId);
        newStat.setViewCount(0L);
        newStat.setLikeCount(0L);
        newStat.setCommentCount(0L);
        newStat.setShareCount(0L);
        return newStat;
    }

    public long getPendingViewTasks() {
        return statisticsQueueService.getPendingViewTaskCount();
    }

    public long getPendingLikeTasks() {
        return statisticsQueueService.getPendingLikeTaskCount();
    }

    public long getPendingShareTasks() {
        return statisticsQueueService.getPendingShareTaskCount();
    }

    public long getTotalPendingTasks() {
        return statisticsQueueService.getTotalPendingTasks();
    }
}
