package com.mobilestore.service;

import com.mobilestore.entity.Statistics;
import com.mobilestore.repository.StatisticsRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class AsyncStatisticsService {

    private static final Logger logger = LoggerFactory.getLogger(AsyncStatisticsService.class);

    @Autowired
    private StatisticsRepository statisticsRepository;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String CACHE_PREFIX = "stats_cache:";
    private static final String SUMMARY_CACHE_KEY = CACHE_PREFIX + "summary:";
    private static final String CHART_CACHE_KEY = CACHE_PREFIX + "chart:";
    private static final String TASK_QUEUE_KEY = "stats_task_queue";
    private static final String TASK_STATUS_PREFIX = "stats_task_status:";

    private static final long SUMMARY_CACHE_TTL = 30;
    private static final long CHART_CACHE_TTL = 15;

    public static class StatisticsTask {
        private String taskId;
        private String appId;
        private String taskType;
        private LocalDate startDate;
        private LocalDate endDate;
        private String status;
        private LocalDateTime createdAt;
        private LocalDateTime completedAt;

        public StatisticsTask() {}

        public StatisticsTask(String appId, String taskType, LocalDate startDate, LocalDate endDate) {
            this.taskId = "task_" + UUID.randomUUID().toString().substring(0, 10);
            this.appId = appId;
            this.taskType = taskType;
            this.startDate = startDate;
            this.endDate = endDate;
            this.status = "pending";
            this.createdAt = LocalDateTime.now();
        }

        public String getTaskId() { return taskId; }
        public void setTaskId(String taskId) { this.taskId = taskId; }
        public String getAppId() { return appId; }
        public void setAppId(String appId) { this.appId = appId; }
        public String getTaskType() { return taskType; }
        public void setTaskType(String taskType) { this.taskType = taskType; }
        public LocalDate getStartDate() { return startDate; }
        public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
        public LocalDate getEndDate() { return endDate; }
        public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
        public LocalDateTime getCompletedAt() { return completedAt; }
        public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    }

    public Map<String, Object> submitSummaryCalculation(String appId) {
        StatisticsTask task = new StatisticsTask(appId, "summary", null, null);
        submitTask(task);
        return createTaskResponse(task);
    }

    public Map<String, Object> submitChartCalculation(String appId, LocalDate startDate, LocalDate endDate) {
        StatisticsTask task = new StatisticsTask(appId, "chart", startDate, endDate);
        submitTask(task);
        return createTaskResponse(task);
    }

    private void submitTask(StatisticsTask task) {
        try {
            String taskJson = objectMapper.writeValueAsString(task);
            redisTemplate.opsForList().rightPush(TASK_QUEUE_KEY, taskJson);
            updateTaskStatus(task.getTaskId(), "queued");
            logger.info("Statistics task submitted: {} - {}", task.getTaskId(), task.getTaskType());
        } catch (Exception e) {
            logger.error("Failed to submit statistics task", e);
            updateTaskStatus(task.getTaskId(), "failed");
        }
    }

    @Async
    public void processTaskQueue() {
        while (true) {
            try {
                Object taskObj = redisTemplate.opsForList().leftPop(TASK_QUEUE_KEY, 5, TimeUnit.SECONDS);
                if (taskObj == null) {
                    break;
                }

                StatisticsTask task = objectMapper.readValue(
                    objectMapper.writeValueAsString(taskObj),
                    StatisticsTask.class
                );

                updateTaskStatus(task.getTaskId(), "processing");

                try {
                    if ("summary".equals(task.getTaskType())) {
                        calculateAndCacheSummary(task.getAppId());
                    } else if ("chart".equals(task.getTaskType())) {
                        calculateAndCacheChart(task.getAppId(), task.getStartDate(), task.getEndDate());
                    }
                    updateTaskStatus(task.getTaskId(), "completed");
                    logger.info("Statistics task completed: {}", task.getTaskId());
                } catch (Exception e) {
                    logger.error("Failed to process statistics task: {}", task.getTaskId(), e);
                    updateTaskStatus(task.getTaskId(), "failed");
                }
            } catch (Exception e) {
                logger.error("Error processing task queue", e);
                break;
            }
        }
    }

    public void calculateAndCacheSummary(String appId) {
        logger.info("Calculating summary statistics for app: {}", appId);

        Long totalDownloads = statisticsRepository.sumDownloadCountByAppId(appId);
        Long totalActiveUsers = statisticsRepository.sumActiveUsersByAppId(appId);
        Double avgRating = statisticsRepository.avgRatingByAppId(appId);
        Long totalFeedbacks = statisticsRepository.sumFeedbackCountByAppId(appId);

        Map<String, Object> summary = new HashMap<>();
        summary.put("totalDownloads", totalDownloads != null ? totalDownloads : 0L);
        summary.put("totalActiveUsers", totalActiveUsers != null ? totalActiveUsers : 0L);
        summary.put("avgRating", avgRating != null ? Math.round(avgRating * 100.0) / 100.0 : 0.0);
        summary.put("totalFeedbacks", totalFeedbacks != null ? totalFeedbacks : 0L);
        summary.put("calculatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        List<Statistics> recentStats = statisticsRepository.findByAppIdOrderByStatDateDesc(appId);
        if (!recentStats.isEmpty()) {
            Statistics latest = recentStats.get(0);
            summary.put("latestDownloads", latest.getDownloadCount());
            summary.put("latestActiveUsers", latest.getActiveUsers());
            summary.put("latestRating", latest.getAvgRating());
            summary.put("latestDate", latest.getStatDate().toString());
        }

        String cacheKey = SUMMARY_CACHE_KEY + appId;
        redisTemplate.opsForValue().set(cacheKey, summary, SUMMARY_CACHE_TTL, TimeUnit.MINUTES);
        logger.info("Summary statistics cached for app: {}", appId);
    }

    public void calculateAndCacheChart(String appId, LocalDate startDate, LocalDate endDate) {
        logger.info("Calculating chart statistics for app: {} from {} to {}", appId, startDate, endDate);

        List<Statistics> stats = statisticsRepository.findByAppIdAndStatDateBetweenOrderByStatDateAsc(appId, startDate, endDate);

        Map<String, Object> chartData = new HashMap<>();
        List<String> labels = new ArrayList<>();
        List<Long> downloads = new ArrayList<>();
        List<Long> activeUsers = new ArrayList<>();
        List<Double> ratings = new ArrayList<>();
        List<Long> feedbacks = new ArrayList<>();

        for (Statistics stat : stats) {
            labels.add(stat.getStatDate().toString());
            downloads.add(stat.getDownloadCount());
            activeUsers.add(stat.getActiveUsers());
            ratings.add(stat.getAvgRating());
            feedbacks.add(stat.getFeedbackCount());
        }

        chartData.put("labels", labels);
        chartData.put("downloads", downloads);
        chartData.put("activeUsers", activeUsers);
        chartData.put("ratings", ratings);
        chartData.put("feedbacks", feedbacks);
        chartData.put("calculatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        String cacheKey = CHART_CACHE_KEY + appId + ":" + startDate + ":" + endDate;
        redisTemplate.opsForValue().set(cacheKey, chartData, CHART_CACHE_TTL, TimeUnit.MINUTES);
        logger.info("Chart statistics cached for app: {}", appId);
    }

    public Map<String, Object> getCachedSummary(String appId) {
        String cacheKey = SUMMARY_CACHE_KEY + appId;
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                return objectMapper.readValue(
                    objectMapper.writeValueAsString(cached),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}
                );
            } catch (Exception e) {
                logger.warn("Failed to parse cached summary", e);
            }
        }
        return null;
    }

    public Map<String, Object> getCachedChart(String appId, LocalDate startDate, LocalDate endDate) {
        String cacheKey = CHART_CACHE_KEY + appId + ":" + startDate + ":" + endDate;
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                return objectMapper.readValue(
                    objectMapper.writeValueAsString(cached),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}
                );
            } catch (Exception e) {
                logger.warn("Failed to parse cached chart", e);
            }
        }
        return null;
    }

    public boolean isSummaryCached(String appId) {
        String cacheKey = SUMMARY_CACHE_KEY + appId;
        return Boolean.TRUE.equals(redisTemplate.hasKey(cacheKey));
    }

    public boolean isChartCached(String appId, LocalDate startDate, LocalDate endDate) {
        String cacheKey = CHART_CACHE_KEY + appId + ":" + startDate + ":" + endDate;
        return Boolean.TRUE.equals(redisTemplate.hasKey(cacheKey));
    }

    private void updateTaskStatus(String taskId, String status) {
        String statusKey = TASK_STATUS_PREFIX + taskId;
        Map<String, Object> statusData = new HashMap<>();
        statusData.put("taskId", taskId);
        statusData.put("status", status);
        statusData.put("updatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        
        try {
            redisTemplate.opsForValue().set(statusKey, statusData, 1, TimeUnit.HOURS);
        } catch (Exception e) {
            logger.warn("Failed to update task status", e);
        }
    }

    public Map<String, Object> getTaskStatus(String taskId) {
        String statusKey = TASK_STATUS_PREFIX + taskId;
        Object cached = redisTemplate.opsForValue().get(statusKey);
        if (cached != null) {
            try {
                return objectMapper.readValue(
                    objectMapper.writeValueAsString(cached),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}
                );
            } catch (Exception e) {
                logger.warn("Failed to parse task status", e);
            }
        }
        return null;
    }

    public void invalidateCache(String appId) {
        Set<String> keysToDelete = new HashSet<>();
        keysToDelete.add(SUMMARY_CACHE_KEY + appId);
        
        Set<String> allKeys = redisTemplate.keys(CHART_CACHE_KEY + appId + "*");
        if (allKeys != null) {
            keysToDelete.addAll(allKeys);
        }

        if (!keysToDelete.isEmpty()) {
            redisTemplate.delete(keysToDelete);
            logger.info("Cache invalidated for app: {}", appId);
        }
    }

    @Scheduled(cron = "0 */5 * * * *")
    public void scheduledTaskProcessing() {
        logger.debug("Running scheduled statistics task processing");
        processTaskQueue();
    }

    @Scheduled(cron = "0 0 * * * *")
    public void scheduledSummaryRefresh() {
        logger.info("Running hourly summary refresh");
        List<String> appIds = statisticsRepository.findAllAppIds();
        for (String appId : appIds) {
            try {
                calculateAndCacheSummary(appId);
            } catch (Exception e) {
                logger.error("Failed to refresh summary for app: {}", appId, e);
            }
        }
    }

    private Map<String, Object> createTaskResponse(StatisticsTask task) {
        Map<String, Object> response = new HashMap<>();
        response.put("taskId", task.getTaskId());
        response.put("taskType", task.getTaskType());
        response.put("status", "queued");
        response.put("message", "统计计算任务已提交，请稍后查询结果");
        return response;
    }
}
