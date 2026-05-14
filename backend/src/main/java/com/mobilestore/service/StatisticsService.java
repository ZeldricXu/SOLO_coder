package com.mobilestore.service;

import com.mobilestore.dto.StatisticsRequest;
import com.mobilestore.entity.Statistics;
import com.mobilestore.repository.StatisticsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
public class StatisticsService {

    private static final Logger logger = LoggerFactory.getLogger(StatisticsService.class);

    @Autowired
    private StatisticsRepository statisticsRepository;

    @Autowired
    private AsyncStatisticsService asyncStatisticsService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final String CACHE_PREFIX = "stats:";
    private static final String PENDING_TASKS_KEY = "stats:pending_tasks";

    public Map<String, Object> getStatistics(StatisticsRequest request) {
        logger.info("Getting statistics for app: {} from {} to {}", request.getAppId(), request.getStartDate(), request.getEndDate());

        Map<String, Object> response = new HashMap<>();
        Map<String, Object> cachedChart = null;
        boolean cacheHit = false;

        if (request.getStartDate() != null && request.getEndDate() != null) {
            cachedChart = asyncStatisticsService.getCachedChart(request.getAppId(), request.getStartDate(), request.getEndDate());
            cacheHit = cachedChart != null;
        }

        if (cacheHit) {
            response.put("cacheHit", true);
            response.put("data", cachedChart);
            response.put("message", "从缓存读取统计数据");
            return response;
        }

        response.put("cacheHit", false);
        
        boolean hasPendingTask = checkAndAddPendingTask(request.getAppId(), "chart");
        if (hasPendingTask) {
            response.put("status", "calculating");
            response.put("message", "统计数据正在计算中，请稍后重试");
            return response;
        }

        Map<String, Object> taskResponse = asyncStatisticsService.submitChartCalculation(
                request.getAppId(),
                request.getStartDate(),
                request.getEndDate()
        );

        asyncStatisticsService.processTaskQueue();

        response.put("status", "submitted");
        response.put("taskId", taskResponse.get("taskId"));
        response.put("message", "统计计算任务已提交，数据将在计算完成后可用");
        response.put("retryAfterSeconds", 5);

        return response;
    }

    public Map<String, Object> getSummaryStatistics(String appId) {
        logger.info("Getting summary statistics for app: {}", appId);

        Map<String, Object> cachedSummary = asyncStatisticsService.getCachedSummary(appId);
        
        if (cachedSummary != null) {
            Map<String, Object> response = new HashMap<>();
            response.put("cacheHit", true);
            response.put("data", cachedSummary);
            response.put("message", "从缓存读取统计摘要");
            return response;
        }

        Map<String, Object> response = new HashMap<>();
        response.put("cacheHit", false);

        boolean hasPendingTask = checkAndAddPendingTask(appId, "summary");
        if (hasPendingTask) {
            response.put("status", "calculating");
            response.put("message", "统计摘要正在计算中，请稍后重试");
            return response;
        }

        Map<String, Object> taskResponse = asyncStatisticsService.submitSummaryCalculation(appId);
        asyncStatisticsService.processTaskQueue();

        response.put("status", "submitted");
        response.put("taskId", taskResponse.get("taskId"));
        response.put("message", "统计摘要计算任务已提交");
        response.put("retryAfterSeconds", 3);

        return response;
    }

    public Map<String, Object> getChartData(StatisticsRequest request) {
        return getStatistics(request);
    }

    public Map<String, Object> getTaskStatus(String taskId) {
        Map<String, Object> taskStatus = asyncStatisticsService.getTaskStatus(taskId);
        
        if (taskStatus == null) {
            Map<String, Object> response = new HashMap<>();
            response.put("exists", false);
            response.put("message", "任务不存在或已过期");
            return response;
        }

        taskStatus.put("exists", true);
        return taskStatus;
    }

    public Map<String, Object> waitForSummary(String appId, int maxWaitSeconds) {
        int waited = 0;
        while (waited < maxWaitSeconds) {
            Map<String, Object> cached = asyncStatisticsService.getCachedSummary(appId);
            if (cached != null) {
                Map<String, Object> response = new HashMap<>();
                response.put("ready", true);
                response.put("data", cached);
                response.put("waitedSeconds", waited);
                return response;
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            waited++;
        }

        Map<String, Object> response = new HashMap<>();
        response.put("ready", false);
        response.put("waitedSeconds", waited);
        response.put("message", "等待超时，数据仍在计算中");
        return response;
    }

    public Map<String, Object> waitForChart(String appId, LocalDate startDate, LocalDate endDate, int maxWaitSeconds) {
        int waited = 0;
        while (waited < maxWaitSeconds) {
            Map<String, Object> cached = asyncStatisticsService.getCachedChart(appId, startDate, endDate);
            if (cached != null) {
                Map<String, Object> response = new HashMap<>();
                response.put("ready", true);
                response.put("data", cached);
                response.put("waitedSeconds", waited);
                return response;
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            waited++;
        }

        Map<String, Object> response = new HashMap<>();
        response.put("ready", false);
        response.put("waitedSeconds", waited);
        response.put("message", "等待超时，数据仍在计算中");
        return response;
    }

    @Async("statisticsTaskExecutor")
    public CompletableFuture<Map<String, Object>> calculateSummaryAsync(String appId) {
        logger.info("Async calculating summary for app: {}", appId);
        asyncStatisticsService.calculateAndCacheSummary(appId);
        Map<String, Object> result = asyncStatisticsService.getCachedSummary(appId);
        return CompletableFuture.completedFuture(result);
    }

    @Async("statisticsTaskExecutor")
    public CompletableFuture<Map<String, Object>> calculateChartAsync(String appId, LocalDate startDate, LocalDate endDate) {
        logger.info("Async calculating chart for app: {}", appId);
        asyncStatisticsService.calculateAndCacheChart(appId, startDate, endDate);
        Map<String, Object> result = asyncStatisticsService.getCachedChart(appId, startDate, endDate);
        return CompletableFuture.completedFuture(result);
    }

    private boolean checkAndAddPendingTask(String appId, String taskType) {
        String taskKey = appId + ":" + taskType;
        Long result = redisTemplate.opsForSet().add(PENDING_TASKS_KEY, taskKey);
        if (result != null && result == 0) {
            return true;
        }
        redisTemplate.expire(PENDING_TASKS_KEY, 5, TimeUnit.MINUTES);
        return false;
    }

    public void clearPendingTask(String appId, String taskType) {
        String taskKey = appId + ":" + taskType;
        redisTemplate.opsForSet().remove(PENDING_TASKS_KEY, taskKey);
    }

    public Map<String, Object> forceRefreshSummary(String appId) {
        logger.info("Force refreshing summary for app: {}", appId);
        asyncStatisticsService.invalidateCache(appId);
        asyncStatisticsService.calculateAndCacheSummary(appId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("refreshed", true);
        response.put("data", asyncStatisticsService.getCachedSummary(appId));
        return response;
    }

    public Statistics createDailyStatistics(String appId, LocalDate date) {
        Optional<Statistics> existing = statisticsRepository.findByAppIdAndStatDate(appId, date);
        if (existing.isPresent()) {
            return existing.get();
        }

        Statistics stats = new Statistics();
        stats.setStatId("stat_" + UUID.randomUUID().toString().substring(0, 8));
        stats.setAppId(appId);
        stats.setStatDate(date);
        stats.setDownloadCount((long) (Math.random() * 500 + 100));
        stats.setActiveUsers((long) (Math.random() * 300 + 50));
        stats.setAvgRating(Math.round((Math.random() * 2 + 3) * 10.0) / 10.0);
        stats.setFeedbackCount((long) (Math.random() * 10 + 1));

        Statistics saved = statisticsRepository.save(stats);
        asyncStatisticsService.invalidateCache(appId);
        return saved;
    }

    public List<Statistics> generateDemoData(String appId, int days) {
        List<Statistics> result = new ArrayList<>();
        LocalDate today = LocalDate.now();

        for (int i = days - 1; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            result.add(createDailyStatistics(appId, date));
        }

        asyncStatisticsService.invalidateCache(appId);
        asyncStatisticsService.calculateAndCacheSummary(appId);

        return result;
    }

    public Map<String, Object> getCacheInfo(String appId) {
        Map<String, Object> info = new HashMap<>();
        info.put("summaryCached", asyncStatisticsService.isSummaryCached(appId));
        
        Set<String> chartKeys = redisTemplate.keys("stats_cache:chart:" + appId + "*");
        info.put("chartCacheCount", chartKeys != null ? chartKeys.size() : 0);
        
        return info;
    }
}
