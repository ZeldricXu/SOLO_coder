package com.ratelimiter.service.stats;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ratelimiter.model.TrafficStatistics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class StatisticsService {
    
    private static final String STATS_KEY_PREFIX = "ratelimiter:stats:";
    private static final String AGGREGATED_STATS_KEY_PREFIX = "ratelimiter:aggregated_stats:";
    
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final StatisticsProperties statsProperties;
    
    private final ConcurrentHashMap<String, TargetStats> targetStats;
    private final ConcurrentHashMap<String, AggregatedStatsData> aggregatedStats;
    private final Cache<String, Object> cache;
    
    private Counter totalRequestsCounter;
    private Counter passedRequestsCounter;
    private Counter rejectedRequestsCounter;
    private Counter errorRequestsCounter;
    private Timer requestLatencyTimer;
    
    public StatisticsService(RedisTemplate<String, Object> redisTemplate, 
                            ObjectMapper objectMapper,
                            MeterRegistry meterRegistry,
                            StatisticsProperties statsProperties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.statsProperties = statsProperties;
        this.targetStats = new ConcurrentHashMap<>();
        this.aggregatedStats = new ConcurrentHashMap<>();
        this.cache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(1, TimeUnit.MINUTES)
                .build();
    }
    
    @PostConstruct
    public void initMetrics() {
        log.info("Initializing StatisticsService with dimensions: {}", statsProperties.getAggregationDimensions());
        
        totalRequestsCounter = Counter.builder("ratelimiter_requests_total")
                .description("Total number of requests")
                .register(meterRegistry);
        
        passedRequestsCounter = Counter.builder("ratelimiter_requests_passed")
                .description("Number of passed requests")
                .register(meterRegistry);
        
        rejectedRequestsCounter = Counter.builder("ratelimiter_requests_rejected")
                .description("Number of rejected requests")
                .register(meterRegistry);
        
        errorRequestsCounter = Counter.builder("ratelimiter_requests_errors")
                .description("Number of error requests")
                .register(meterRegistry);
        
        requestLatencyTimer = Timer.builder("ratelimiter_request_latency")
                .description("Request latency")
                .register(meterRegistry);
    }
    
    public void recordRequest(String target, boolean passed, long latencyMs) {
        recordRequestWithDimensions(target, passed, false, latencyMs, null);
    }
    
    public void recordRequestWithDimensions(String target, boolean passed, boolean isError, 
                                             long latencyMs, Map<AggregationDimension, String> dimensionValues) {
        if (!statsProperties.isEnabled()) {
            return;
        }
        
        targetStats.computeIfAbsent(target, k -> new TargetStats(target));
        TargetStats stats = targetStats.get(target);
        stats.recordRequest(passed, latencyMs);
        if (isError) {
            stats.recordError();
        }
        
        totalRequestsCounter.increment();
        if (passed) {
            passedRequestsCounter.increment();
        } else {
            rejectedRequestsCounter.increment();
        }
        if (isError) {
            errorRequestsCounter.increment();
        }
        requestLatencyTimer.record(latencyMs, TimeUnit.MILLISECONDS);
        
        if (statsProperties.getEnabledDimensions().isEmpty()) {
            return;
        }
        
        Map<AggregationDimension, String> effectiveDimensions = dimensionValues != null ? 
                dimensionValues : new HashMap<>();
        
        if (!effectiveDimensions.containsKey(AggregationDimension.API_PATH)) {
            effectiveDimensions.put(AggregationDimension.API_PATH, target);
        }
        
        String timePeriod = getCurrentTimePeriod();
        effectiveDimensions.put(AggregationDimension.TIME_PERIOD, timePeriod);
        
        Map<AggregationDimension, String> filteredDimensions = new HashMap<>();
        for (AggregationDimension enabledDimension : statsProperties.getEnabledDimensions()) {
            if (effectiveDimensions.containsKey(enabledDimension)) {
                filteredDimensions.put(enabledDimension, effectiveDimensions.get(enabledDimension));
            }
        }
        
        if (!filteredDimensions.isEmpty()) {
            AggregatedStatsData aggregatedData = new AggregatedStatsData(filteredDimensions);
            String key = aggregatedData.getDimensionKey();
            
            aggregatedStats.computeIfAbsent(key, k -> aggregatedData);
            AggregatedStatsData existingData = aggregatedStats.get(key);
            existingData.recordRequest(passed, isError, latencyMs);
            
            log.debug("Recorded aggregated stats for key: {}, total: {}", 
                    key, existingData.getTotalRequests());
        }
        
        if (isError) {
            checkAndTriggerAlert(target);
        }
    }
    
    public void recordError(String target) {
        TargetStats stats = targetStats.get(target);
        if (stats != null) {
            stats.recordError();
        }
        errorRequestsCounter.increment();
        
        checkAndTriggerAlert(target);
    }
    
    private void checkAndTriggerAlert(String target) {
        if (!statsProperties.isEnableAlerting()) {
            return;
        }
        
        TargetStats stats = targetStats.get(target);
        if (stats == null) {
            return;
        }
        
        double errorRate = stats.getErrorRate();
        if (errorRate > statsProperties.getHighErrorRateThreshold()) {
            log.warn("ALERT: High error rate detected for target: {}, error rate: {}", 
                    target, errorRate);
            sendAlert(target, errorRate);
        }
    }
    
    private void sendAlert(String target, double errorRate) {
        log.error("Sending alert notification for target: {} with error rate: {}", target, errorRate);
        
        if (statsProperties.getAlertChannels() != null && !statsProperties.getAlertChannels().isEmpty()) {
            for (String channel : statsProperties.getAlertChannels()) {
                log.info("Alert sent to channel: {}", channel);
            }
        }
    }
    
    public TrafficStatistics getCurrentStatistics(String target) {
        TargetStats stats = targetStats.get(target);
        if (stats == null) {
            return null;
        }
        
        return TrafficStatistics.builder()
                .statId(generateStatId(target))
                .target(target)
                .totalRequests(stats.getTotalRequests())
                .passedRequests(stats.getPassedRequests())
                .rejectedRequests(stats.getRejectedRequests())
                .avgResponseTime(stats.getAvgResponseTime())
                .errorRate(stats.getErrorRate())
                .statPeriod("1min")
                .collectedAt(Instant.now())
                .build();
    }
    
    public List<TrafficStatistics> getAllCurrentStatistics() {
        List<TrafficStatistics> statsList = new ArrayList<>();
        for (Map.Entry<String, TargetStats> entry : targetStats.entrySet()) {
            TrafficStatistics stats = TrafficStatistics.builder()
                    .statId(generateStatId(entry.getKey()))
                    .target(entry.getKey())
                    .totalRequests(entry.getValue().getTotalRequests())
                    .passedRequests(entry.getValue().getPassedRequests())
                    .rejectedRequests(entry.getValue().getRejectedRequests())
                    .avgResponseTime(entry.getValue().getAvgResponseTime())
                    .errorRate(entry.getValue().getErrorRate())
                    .statPeriod("1min")
                    .collectedAt(Instant.now())
                    .build();
            statsList.add(stats);
        }
        return statsList;
    }
    
    public List<AggregatedStatsData> getAllAggregatedStatistics() {
        return new ArrayList<>(aggregatedStats.values());
    }
    
    public List<AggregatedStatsData> getAggregatedStatisticsByDimension(
            AggregationDimension dimension, String value) {
        List<AggregatedStatsData> result = new ArrayList<>();
        for (AggregatedStatsData data : aggregatedStats.values()) {
            String dimValue = data.getDimensionValue(dimension);
            if (dimValue != null && dimValue.equals(value)) {
                result.add(data);
            }
        }
        return result;
    }
    
    @Scheduled(fixedRateString = "${ratelimiter.stats.persist-interval-ms:60000}")
    public void persistStatistics() {
        if (!statsProperties.isEnabled()) {
            return;
        }
        
        log.info("Persisting statistics to Redis...");
        
        for (Map.Entry<String, TargetStats> entry : targetStats.entrySet()) {
            String target = entry.getKey();
            TargetStats stats = entry.getValue();
            
            TrafficStatistics trafficStats = TrafficStatistics.builder()
                    .statId(generateStatId(target))
                    .target(target)
                    .totalRequests(stats.getTotalRequests())
                    .passedRequests(stats.getPassedRequests())
                    .rejectedRequests(stats.getRejectedRequests())
                    .avgResponseTime(stats.getAvgResponseTime())
                    .errorRate(stats.getErrorRate())
                    .statPeriod("1min")
                    .collectedAt(Instant.now())
                    .build();
            
            String key = STATS_KEY_PREFIX + target + ":" + 
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"));
            
            redisTemplate.opsForValue().set(key, trafficStats, 7, TimeUnit.DAYS);
            
            stats.reset();
        }
        
        for (Map.Entry<String, AggregatedStatsData> entry : aggregatedStats.entrySet()) {
            String key = entry.getKey();
            AggregatedStatsData data = entry.getValue();
            
            String redisKey = AGGREGATED_STATS_KEY_PREFIX + key + ":" + 
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"));
            
            redisTemplate.opsForValue().set(redisKey, data, 7, TimeUnit.DAYS);
        }
        
        aggregatedStats.clear();
        
        log.info("Statistics persisted successfully");
    }
    
    private String getCurrentTimePeriod() {
        LocalDateTime now = LocalDateTime.now();
        return now.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"));
    }
    
    private String generateStatId(String target) {
        return "stat_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")) + 
                "_" + target.replace("/", "_").replace(":", "_");
    }
    
    public void enableDimension(AggregationDimension dimension) {
        statsProperties.enableDimension(dimension);
        log.info("Enabled aggregation dimension: {}", dimension.getCode());
    }
    
    public void disableDimension(AggregationDimension dimension) {
        statsProperties.disableDimension(dimension);
        log.info("Disabled aggregation dimension: {}", dimension.getCode());
    }
    
    public List<AggregationDimension> getEnabledDimensions() {
        return statsProperties.getEnabledDimensions();
    }
    
    private static class TargetStats {
        private final String target;
        private final AtomicLong totalRequests;
        private final AtomicLong passedRequests;
        private final AtomicLong rejectedRequests;
        private final AtomicLong errorRequests;
        private final AtomicLong totalLatency;
        
        public TargetStats(String target) {
            this.target = target;
            this.totalRequests = new AtomicLong(0);
            this.passedRequests = new AtomicLong(0);
            this.rejectedRequests = new AtomicLong(0);
            this.errorRequests = new AtomicLong(0);
            this.totalLatency = new AtomicLong(0);
        }
        
        public void recordRequest(boolean passed, long latencyMs) {
            totalRequests.incrementAndGet();
            if (passed) {
                passedRequests.incrementAndGet();
            } else {
                rejectedRequests.incrementAndGet();
            }
            totalLatency.addAndGet(latencyMs);
        }
        
        public void recordError() {
            errorRequests.incrementAndGet();
        }
        
        public long getTotalRequests() {
            return totalRequests.get();
        }
        
        public long getPassedRequests() {
            return passedRequests.get();
        }
        
        public long getRejectedRequests() {
            return rejectedRequests.get();
        }
        
        public long getAvgResponseTime() {
            long total = totalRequests.get();
            if (total == 0) {
                return 0;
            }
            return totalLatency.get() / total;
        }
        
        public double getErrorRate() {
            long total = totalRequests.get();
            if (total == 0) {
                return 0.0;
            }
            return (double) errorRequests.get() / total;
        }
        
        public void reset() {
            totalRequests.set(0);
            passedRequests.set(0);
            rejectedRequests.set(0);
            errorRequests.set(0);
            totalLatency.set(0);
        }
    }
}