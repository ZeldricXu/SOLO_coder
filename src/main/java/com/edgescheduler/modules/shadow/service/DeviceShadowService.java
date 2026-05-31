package com.edgescheduler.modules.shadow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edgescheduler.common.exception.BusinessException;
import com.edgescheduler.common.exception.ValidationException;
import com.edgescheduler.common.util.IdGenerator;
import com.edgescheduler.common.util.SignatureUtil;
import com.edgescheduler.modules.shadow.domain.DeviceShadow;
import com.edgescheduler.modules.shadow.domain.ShadowMonitorMetric;
import com.edgescheduler.modules.shadow.mapper.DeviceShadowMapper;
import com.edgescheduler.modules.shadow.mapper.ShadowMonitorMetricMapper;
import com.edgescheduler.infrastructure.mapper.ConfigMapper;
import com.edgescheduler.domain.entity.ConfigEntity;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceShadowService {

    private final DeviceShadowMapper deviceShadowMapper;
    private final ConfigMapper configMapper;
    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final MeterRegistry meterRegistry;
    private final ShadowMonitorMetricMapper shadowMonitorMetricMapper;

    private final Map<String, Timer.Sample> syncTimers = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> pendingSyncCount = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> lastSyncLatency = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> deviceLocks = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> lockUsage = new ConcurrentHashMap<>();

    private static final double LATENCY_WARNING_THRESHOLD_MS = 5000;
    private static final double LATENCY_ERROR_THRESHOLD_MS = 30000;
    private static final int CONFLICT_WARNING_THRESHOLD = 10;
    private static final int MAX_METRICS_RETENTION_HOURS = 24;
    private static final int MAX_DEVICE_ID_LENGTH = 128;
    private static final int LOCK_CLEANUP_THRESHOLD = 1000;

    private ReentrantLock getDeviceLock(String deviceId) {
        return deviceLocks.computeIfAbsent(deviceId, k -> new ReentrantLock());
    }

    private void acquireLock(String deviceId) {
        ReentrantLock lock = getDeviceLock(deviceId);
        lock.lock();
        lockUsage.computeIfAbsent(deviceId, k -> new AtomicInteger(0)).incrementAndGet();
    }

    private void releaseLock(String deviceId) {
        ReentrantLock lock = deviceLocks.get(deviceId);
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
            AtomicInteger usage = lockUsage.get(deviceId);
            if (usage != null && usage.decrementAndGet() <= 0) {
                if (deviceLocks.size() > LOCK_CLEANUP_THRESHOLD) {
                    deviceLocks.remove(deviceId);
                    lockUsage.remove(deviceId);
                }
            }
        }
    }

    private void validateDeviceId(String deviceId) {
        if (deviceId == null || deviceId.trim().isEmpty()) {
            throw new ValidationException("设备ID不能为空");
        }
        if (deviceId.length() > MAX_DEVICE_ID_LENGTH) {
            throw new ValidationException("设备ID长度不能超过 " + MAX_DEVICE_ID_LENGTH + " 字符");
        }
    }

    @Cacheable(value = "deviceShadow", key = "#deviceId")
    public Mono<DeviceShadow> getShadow(String deviceId) {
        validateDeviceId(deviceId);
        DeviceShadow shadow = deviceShadowMapper.selectOne(
                new LambdaQueryWrapper<DeviceShadow>()
                        .eq(DeviceShadow::getDeviceId, deviceId));
        if (shadow == null) {
            return Mono.error(new BusinessException("设备影子不存在"));
        }
        return Mono.just(shadow);
    }

    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(value = "deviceShadow", key = "#deviceId")
    public Mono<DeviceShadow> updateDesiredState(String deviceId, Map<String, Object> desiredState,
                                                  String signature, long timestamp) {
        validateDeviceId(deviceId);
        if (desiredState == null || desiredState.isEmpty()) {
            return Mono.error(new ValidationException("期望状态不能为空"));
        }

        validateRequest(signature, timestamp, deviceId, desiredState);

        Timer.Sample sample = Timer.start(meterRegistry);
        LocalDateTime requestTime = LocalDateTime.now();

        acquireLock(deviceId);
        try {
            ConfigEntity config = loadConfig("shadow_sync");
            Map<String, Object> parameters = config.getParameters();

            DeviceShadow shadow = getOrCreateShadow(deviceId);

            LocalDateTime lastReportedTime = shadow.getLastSyncTime();
            if (lastReportedTime != null) {
                long latencyMs = Duration.between(lastReportedTime, requestTime).toMillis();
                shadow.setSyncLatencyMs(latencyMs);
                recordSyncLatency(deviceId, latencyMs);
            }

            Map<String, Object> conflicts = detectConflicts(desiredState, shadow.getReportedState());
            if (!conflicts.isEmpty()) {
                handleStateConflict(shadow, conflicts);
            }

            Map<String, Object> transformedState = transformState(desiredState, parameters);
            shadow.setDesiredState(transformedState);
            shadow.setDeltaState(calculateDelta(transformedState, shadow.getReportedState()));
            shadow.setShadowVersion(shadow.getShadowVersion() + 1);
            shadow.setLastSyncTime(requestTime);
            shadow.setSyncStatus("SYNCING");
            shadow.setMonitorStatus(calculateMonitorStatus(shadow));
            shadow.setLastMetricUpdate(requestTime);

            deviceShadowMapper.updateById(shadow);

            updateCacheAndIndex(shadow);
            recordMonitorMetric(deviceId, "desired_state_update", shadow.getShadowVersion().doubleValue(), "version", null);
            recordMonitorMetric(deviceId, "delta_size", shadow.getDeltaState().size(), "count", null);
            updateMetrics("desired_state_updated", shadow);

            return Mono.just(shadow);
        } finally {
            releaseLock(deviceId);
            sample.stop(Timer.builder("edge_scheduler_shadow_desired_update_duration")
                    .description("Duration of desired state update")
                    .tag("deviceId", deviceId)
                    .register(meterRegistry));
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(value = "deviceShadow", key = "#deviceId")
    public Mono<DeviceShadow> updateReportedState(String deviceId, Map<String, Object> reportedState) {
        validateDeviceId(deviceId);
        if (reportedState == null || reportedState.isEmpty()) {
            return Mono.error(new ValidationException("上报状态不能为空"));
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        LocalDateTime reportTime = LocalDateTime.now();

        acquireLock(deviceId);
        try {
            DeviceShadow shadow = getOrCreateShadow(deviceId);

            LocalDateTime lastDesiredTime = shadow.getLastSyncTime();
            if (lastDesiredTime != null) {
                long latencyMs = Duration.between(lastDesiredTime, reportTime).toMillis();
                shadow.setSyncLatencyMs(latencyMs);
                recordSyncLatency(deviceId, latencyMs);
            }

            Map<String, Object> conflicts = detectConflicts(shadow.getDesiredState(), reportedState);
            if (!conflicts.isEmpty()) {
                handleStateConflict(shadow, conflicts);
            }

            shadow.setReportedState(reportedState);
            shadow.setDeltaState(calculateDelta(shadow.getDesiredState(), reportedState));
            shadow.setShadowVersion(shadow.getShadowVersion() + 1);
            shadow.setLastSyncTime(reportTime);
            shadow.setSyncStatus("SYNCED");
            shadow.setMonitorStatus(calculateMonitorStatus(shadow));
            shadow.setLastMetricUpdate(reportTime);

            deviceShadowMapper.updateById(shadow);

            updateCacheAndIndex(shadow);
            recordMonitorMetric(deviceId, "reported_state_update", shadow.getShadowVersion().doubleValue(), "version", null);
            recordMonitorMetric(deviceId, "reported_data_points", reportedState.size(), "count", null);
            updateMetrics("reported_state_updated", shadow);

            Timer.Sample existingTimer = syncTimers.remove(deviceId);
            if (existingTimer != null) {
                existingTimer.stop(Timer.builder("edge_scheduler_shadow_full_sync_duration")
                        .description("Full sync round-trip duration")
                        .tag("deviceId", deviceId)
                        .register(meterRegistry));
            }

            return Mono.just(shadow);
        } finally {
            releaseLock(deviceId);
            sample.stop(Timer.builder("edge_scheduler_shadow_reported_update_duration")
                    .description("Duration of reported state update")
                    .tag("deviceId", deviceId)
                    .register(meterRegistry));
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Mono<DeviceShadow> createShadow(String deviceId) {
        validateDeviceId(deviceId);

        acquireLock(deviceId);
        try {
            DeviceShadow existing = deviceShadowMapper.selectOne(
                    new LambdaQueryWrapper<DeviceShadow>()
                            .eq(DeviceShadow::getDeviceId, deviceId));
            if (existing != null) {
                return Mono.just(existing);
            }

            DeviceShadow shadow = new DeviceShadow();
            shadow.setDeviceId(deviceId);
            shadow.setShadowVersion(1);
            shadow.setDesiredState(new HashMap<>());
            shadow.setReportedState(new HashMap<>());
            shadow.setDeltaState(new HashMap<>());
            shadow.setSyncStatus("PENDING");
            shadow.setLastSyncTime(LocalDateTime.now());
            shadow.setSyncLatencyMs(0L);
            shadow.setConflictCount(0);
            shadow.setMonitorStatus("NORMAL");
            shadow.setLastMetricUpdate(LocalDateTime.now());

            deviceShadowMapper.insert(shadow);
            updateMetrics("shadow_created", shadow);
            recordMonitorMetric(deviceId, "shadow_created", 1.0, "count", null);

            pendingSyncCount.computeIfAbsent(deviceId, k -> new AtomicLong(0));
            lastSyncLatency.computeIfAbsent(deviceId, k -> new AtomicLong(0));

            Gauge.builder("edge_scheduler_shadow_pending_sync", pendingSyncCount.get(deviceId), AtomicLong::get)
                    .description("Pending sync count for device")
                    .tag("deviceId", deviceId)
                    .register(meterRegistry);

            Gauge.builder("edge_scheduler_shadow_sync_latency_ms", lastSyncLatency.get(deviceId), AtomicLong::get)
                    .description("Last sync latency in ms")
                    .tag("deviceId", deviceId)
                    .register(meterRegistry);

            return Mono.just(shadow);
        } finally {
            releaseLock(deviceId);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(value = "deviceShadow", key = "#deviceId")
    public Mono<Void> deleteShadow(String deviceId) {
        validateDeviceId(deviceId);

        acquireLock(deviceId);
        try {
            DeviceShadow shadow = deviceShadowMapper.selectOne(
                    new LambdaQueryWrapper<DeviceShadow>()
                            .eq(DeviceShadow::getDeviceId, deviceId));
            if (shadow != null) {
                deviceShadowMapper.deleteById(shadow.getId());
                redisTemplate.delete("shadow:" + deviceId)
                        .doOnError(e -> log.warn("Failed to delete shadow cache for {}: {}", deviceId, e.getMessage()))
                        .subscribe();
                updateMetrics("shadow_deleted", shadow);
                pendingSyncCount.remove(deviceId);
                lastSyncLatency.remove(deviceId);
                syncTimers.remove(deviceId);
            }
            return Mono.empty();
        } finally {
            releaseLock(deviceId);
        }
    }

    public Mono<Map<String, Object>> getDelta(String deviceId) {
        return getShadow(deviceId)
                .map(DeviceShadow::getDeltaState);
    }

    public Mono<Map<String, Object>> getMonitorMetrics(String deviceId, LocalDateTime startTime, LocalDateTime endTime, String metricType) {
        Map<String, Object> result = new HashMap<>();
        DeviceShadow shadow = deviceShadowMapper.selectOne(
                new LambdaQueryWrapper<DeviceShadow>()
                        .eq(DeviceShadow::getDeviceId, deviceId));
        if (shadow == null) {
            return Mono.error(new BusinessException("设备影子不存在"));
        }

        result.put("currentShadow", shadow);

        LambdaQueryWrapper<ShadowMonitorMetric> query = new LambdaQueryWrapper<ShadowMonitorMetric>()
                .eq(ShadowMonitorMetric::getDeviceId, deviceId)
                .ge(startTime != null, ShadowMonitorMetric::getTimestamp, startTime)
                .le(endTime != null, ShadowMonitorMetric::getTimestamp, endTime)
                .eq(metricType != null, ShadowMonitorMetric::getMetricType, metricType)
                .orderByDesc(ShadowMonitorMetric::getTimestamp)
                .last("LIMIT 100");

        result.put("metrics", shadowMonitorMetricMapper.selectList(query));
        return Mono.just(result);
    }

    public Mono<Map<String, Object>> getShadowHealthStatus(String deviceId) {
        return getShadow(deviceId)
                .map(shadow -> {
                    Map<String, Object> health = new HashMap<>();
                    health.put("deviceId", deviceId);
                    health.put("monitorStatus", shadow.getMonitorStatus());
                    health.put("syncStatus", shadow.getSyncStatus());
                    health.put("shadowVersion", shadow.getShadowVersion());
                    health.put("syncLatencyMs", shadow.getSyncLatencyMs());
                    health.put("conflictCount", shadow.getConflictCount());
                    health.put("lastSyncTime", shadow.getLastSyncTime());
                    health.put("lastMetricUpdate", shadow.getLastMetricUpdate());
                    health.put("deltaSize", shadow.getDeltaState().size());
                    health.put("desiredStateSize", shadow.getDesiredState().size());
                    health.put("reportedStateSize", shadow.getReportedState().size());

                    long stalenessMs = Duration.between(shadow.getLastSyncTime(), LocalDateTime.now()).toMillis();
                    health.put("stalenessMs", stalenessMs);
                    health.put("isStale", stalenessMs > LATENCY_ERROR_THRESHOLD_MS);

                    health.put("healthScore", calculateHealthScore(shadow));

                    return health;
                });
    }

    public Flux<DeviceShadow> getShadowsByMonitorStatus(String monitorStatus) {
        List<DeviceShadow> shadows = deviceShadowMapper.selectList(
                new LambdaQueryWrapper<DeviceShadow>()
                        .eq(monitorStatus != null, DeviceShadow::getMonitorStatus, monitorStatus)
                        .orderByDesc(DeviceShadow::getLastSyncTime));
        return Flux.fromIterable(shadows);
    }

    @Scheduled(fixedRate = 60000)
    @Transactional(rollbackFor = Exception.class)
    public void monitorShadowHealth() {
        List<DeviceShadow> allShadows = deviceShadowMapper.selectList(
                new LambdaQueryWrapper<DeviceShadow>()
                        .eq(DeviceShadow::getDeleted, 0));

        for (DeviceShadow shadow : allShadows) {
            String oldStatus = shadow.getMonitorStatus();
            String newStatus = calculateMonitorStatus(shadow);

            if (!oldStatus.equals(newStatus)) {
                shadow.setMonitorStatus(newStatus);
                shadow.setLastMetricUpdate(LocalDateTime.now());
                deviceShadowMapper.updateById(shadow);
                log.warn("Device shadow {} monitor status changed from {} to {}", 
                        shadow.getDeviceId(), oldStatus, newStatus);
                recordMonitorMetric(shadow.getDeviceId(), "monitor_status_change", 1.0, "count", 
                        Map.of("oldStatus", oldStatus, "newStatus", newStatus));
            }

            long stalenessMs = Duration.between(shadow.getLastSyncTime(), LocalDateTime.now()).toMillis();
            if (stalenessMs > LATENCY_ERROR_THRESHOLD_MS && shadow.getSyncStatus().equals("SYNCING")) {
                pendingSyncCount.computeIfAbsent(shadow.getDeviceId(), k -> new AtomicLong(0)).incrementAndGet();
                meterRegistry.counter("edge_scheduler_shadow_sync_timeout", "deviceId", shadow.getDeviceId()).increment();
            }
        }
    }

    @Scheduled(cron = "0 0 1 * * ?")
    @Transactional(rollbackFor = Exception.class)
    public void cleanupOldMetrics() {
        LocalDateTime cutoffTime = LocalDateTime.now().minusHours(MAX_METRICS_RETENTION_HOURS);
        int deleted = shadowMonitorMetricMapper.delete(
                new LambdaQueryWrapper<ShadowMonitorMetric>()
                        .lt(ShadowMonitorMetric::getTimestamp, cutoffTime));
        log.info("Cleaned up {} old shadow monitor metrics", deleted);
    }

    private Map<String, Object> detectConflicts(Map<String, Object> desired, Map<String, Object> reported) {
        Map<String, Object> conflicts = new HashMap<>();
        if (desired == null || reported == null) {
            return conflicts;
        }
        desired.forEach((key, value) -> {
            if (reported.containsKey(key) && !value.equals(reported.get(key))) {
                conflicts.put(key, Map.of("desired", value, "reported", reported.get(key)));
            }
        });
        return conflicts;
    }

    private synchronized void handleStateConflict(DeviceShadow shadow, Map<String, Object> conflicts) {
        int newCount = (shadow.getConflictCount() != null ? shadow.getConflictCount() : 0) + 1;
        shadow.setConflictCount(newCount);
        shadow.setLastConflictTime(LocalDateTime.now());
        log.warn("State conflict detected for device {}: {}", shadow.getDeviceId(), conflicts);
        meterRegistry.counter("edge_scheduler_shadow_state_conflicts", "deviceId", shadow.getDeviceId()).increment();
        recordMonitorMetric(shadow.getDeviceId(), "state_conflict", (double) newCount, "count", conflicts);
    }

    private String calculateMonitorStatus(DeviceShadow shadow) {
        if (shadow.getConflictCount() != null && shadow.getConflictCount() >= CONFLICT_WARNING_THRESHOLD) {
            return "CONFLICT_RISK";
        }
        if (shadow.getSyncLatencyMs() != null && shadow.getSyncLatencyMs() > LATENCY_ERROR_THRESHOLD_MS) {
            return "ERROR";
        }
        if (shadow.getSyncLatencyMs() != null && shadow.getSyncLatencyMs() > LATENCY_WARNING_THRESHOLD_MS) {
            return "WARNING";
        }
        if (!shadow.getDeltaState().isEmpty()) {
            return "SYNCING";
        }
        return "NORMAL";
    }

    private double calculateHealthScore(DeviceShadow shadow) {
        double score = 100.0;

        if (shadow.getSyncLatencyMs() != null) {
            if (shadow.getSyncLatencyMs() > LATENCY_ERROR_THRESHOLD_MS) {
                score -= 40;
            } else if (shadow.getSyncLatencyMs() > LATENCY_WARNING_THRESHOLD_MS) {
                score -= 20;
            }
        }

        if (shadow.getConflictCount() != null) {
            score -= Math.min(shadow.getConflictCount() * 2, 30);
        }

        score -= Math.min(shadow.getDeltaState().size() * 1.5, 20);

        long stalenessMs = Duration.between(shadow.getLastSyncTime(), LocalDateTime.now()).toMillis();
        if (stalenessMs > LATENCY_ERROR_THRESHOLD_MS) {
            score -= Math.min(stalenessMs / 10000.0, 30);
        }

        return Math.max(0, Math.min(100, score));
    }

    private void recordSyncLatency(String deviceId, long latencyMs) {
        lastSyncLatency.computeIfAbsent(deviceId, k -> new AtomicLong(0)).set(latencyMs);
        
        DistributionSummary.builder("edge_scheduler_shadow_latency_distribution_ms")
                .description("Distribution of sync latencies")
                .tag("deviceId", deviceId)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry)
                .record(latencyMs);
    }

    @Transactional(rollbackFor = Exception.class)
    public void recordMonitorMetric(String deviceId, String metricType, Double metricValue, String metricUnit, Map<String, Object> tags) {
        ShadowMonitorMetric metric = new ShadowMonitorMetric();
        metric.setMetricId(IdGenerator.generateId("metric"));
        metric.setDeviceId(deviceId);
        metric.setMetricType(metricType);
        metric.setMetricValue(metricValue);
        metric.setMetricUnit(metricUnit);
        metric.setTimestamp(LocalDateTime.now());
        metric.setTags(tags);
        shadowMonitorMetricMapper.insert(metric);
    }

    private void validateRequest(String signature, long timestamp, String deviceId, Map<String, Object> params) {
        if (!SignatureUtil.validateTimestamp(timestamp, 300)) {
            throw new ValidationException("请求已过期");
        }

        Map<String, Object> validationParams = new HashMap<>(params);
        validationParams.put("deviceId", deviceId);
        validationParams.put("timestamp", timestamp);

        if (!SignatureUtil.validateSignature(validationParams, signature)) {
            throw new ValidationException("签名验证失败");
        }
    }

    private ConfigEntity loadConfig(String namespace) {
        ConfigEntity config = configMapper.selectOne(
                new LambdaQueryWrapper<ConfigEntity>()
                        .eq(ConfigEntity::getNamespace, namespace)
                        .eq(ConfigEntity::getEnabled, true)
                        .orderByDesc(ConfigEntity::getVersion)
                        .last("LIMIT 1"));
        if (config == null) {
            throw new BusinessException("配置不存在: " + namespace);
        }
        return config;
    }

    private DeviceShadow getOrCreateShadow(String deviceId) {
        DeviceShadow shadow = deviceShadowMapper.selectOne(
                new LambdaQueryWrapper<DeviceShadow>()
                        .eq(DeviceShadow::getDeviceId, deviceId));
        if (shadow == null) {
            shadow = new DeviceShadow();
            shadow.setDeviceId(deviceId);
            shadow.setShadowVersion(1);
            shadow.setDesiredState(new HashMap<>());
            shadow.setReportedState(new HashMap<>());
            shadow.setDeltaState(new HashMap<>());
            shadow.setSyncStatus("PENDING");
            shadow.setLastSyncTime(LocalDateTime.now());
            shadow.setSyncLatencyMs(0L);
            shadow.setConflictCount(0);
            shadow.setMonitorStatus("NORMAL");
            shadow.setLastMetricUpdate(LocalDateTime.now());
            int inserted = deviceShadowMapper.insert(shadow);
            if (inserted == 0) {
                shadow = deviceShadowMapper.selectOne(
                        new LambdaQueryWrapper<DeviceShadow>()
                                .eq(DeviceShadow::getDeviceId, deviceId));
            }
        }
        return shadow;
    }

    private Map<String, Object> transformState(Map<String, Object> state, Map<String, Object> configParams) {
        Map<String, Object> transformed = new HashMap<>(state);
        String mappingRule = (String) configParams.get("mappingRule");
        if (mappingRule != null && !mappingRule.isEmpty()) {
            transformed.put("_mappingApplied", mappingRule);
        }
        return transformed;
    }

    private Map<String, Object> calculateDelta(Map<String, Object> desired, Map<String, Object> reported) {
        Map<String, Object> delta = new HashMap<>();
        if (desired == null) {
            return delta;
        }
        desired.forEach((key, value) -> {
            if (reported == null || !value.equals(reported.get(key))) {
                delta.put(key, value);
            }
        });
        return delta;
    }

    private void updateCacheAndIndex(DeviceShadow shadow) {
        String cacheKey = "shadow:" + shadow.getDeviceId();
        redisTemplate.opsForValue().set(cacheKey, shadow, 10, TimeUnit.MINUTES)
                .doOnError(e -> log.warn("Failed to update shadow cache for {}: {}", shadow.getDeviceId(), e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .subscribe();
        redisTemplate.opsForSet().add("shadow:index:" + shadow.getSyncStatus(), shadow.getDeviceId())
                .doOnError(e -> log.warn("Failed to update sync index for {}: {}", shadow.getDeviceId(), e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .subscribe();
        redisTemplate.opsForSet().add("shadow:monitor:" + shadow.getMonitorStatus(), shadow.getDeviceId())
                .doOnError(e -> log.warn("Failed to update monitor index for {}: {}", shadow.getDeviceId(), e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }

    private void updateMetrics(String action, DeviceShadow shadow) {
        Counter.builder("edge_scheduler_shadow_operations_total")
                .description("Total shadow operations")
                .tag("action", action)
                .tag("deviceId", shadow.getDeviceId())
                .tag("monitorStatus", shadow.getMonitorStatus())
                .tag("syncStatus", shadow.getSyncStatus())
                .register(meterRegistry)
                .increment();
    }

    public Mono<Map<String, Object>> getMonitorOverview() {
        return Mono.fromCallable(() -> {
            Map<String, Object> overview = new HashMap<>();

            List<DeviceShadow> allShadows = deviceShadowMapper.selectList(
                    new LambdaQueryWrapper<DeviceShadow>()
                            .select(DeviceShadow::getDeviceId, DeviceShadow::getMonitorStatus,
                                    DeviceShadow::getSyncStatus, DeviceShadow::getHealthScore,
                                    DeviceShadow::getSyncLatencyMs, DeviceShadow::getConflictCount));

            overview.put("totalDevices", allShadows.size());

            Map<String, Integer> statusCounts = new HashMap<>();
            for (DeviceShadow shadow : allShadows) {
                String status = shadow.getMonitorStatus() != null ? shadow.getMonitorStatus() : "UNKNOWN";
                statusCounts.put(status, statusCounts.getOrDefault(status, 0) + 1);
            }
            overview.put("statusDistribution", statusCounts);

            double avgHealthScore = allShadows.stream()
                    .mapToDouble(s -> s.getHealthScore() != null ? s.getHealthScore() : 0.0)
                    .average()
                    .orElse(0.0);
            overview.put("averageHealthScore", Math.round(avgHealthScore * 100.0) / 100.0);

            double avgLatency = allShadows.stream()
                    .mapToLong(s -> s.getSyncLatencyMs() != null ? s.getSyncLatencyMs() : 0L)
                    .average()
                    .orElse(0.0);
            overview.put("averageSyncLatencyMs", Math.round(avgLatency));

            int totalConflicts = allShadows.stream()
                    .mapToInt(s -> s.getConflictCount() != null ? s.getConflictCount() : 0)
                    .sum();
            overview.put("totalConflicts", totalConflicts);

            long staleDevices = allShadows.stream()
                    .filter(s -> s.getSyncLatencyMs() != null && s.getSyncLatencyMs() > LATENCY_WARNING_THRESHOLD_MS)
                    .count();
            overview.put("staleDevices", staleDevices);

            long highRiskDevices = allShadows.stream()
                    .filter(s -> s.getHealthScore() != null && s.getHealthScore() < 50.0)
                    .count();
            overview.put("highRiskDevices", highRiskDevices);

            long healthyDevices = allShadows.stream()
                    .filter(s -> s.getHealthScore() != null && s.getHealthScore() >= 80.0)
                    .count();
            overview.put("healthyDevices", healthyDevices);

            overview.put("warningThresholdMs", LATENCY_WARNING_THRESHOLD_MS);
            overview.put("errorThresholdMs", LATENCY_ERROR_THRESHOLD_MS);
            overview.put("conflictWarningThreshold", CONFLICT_WARNING_THRESHOLD);

            return overview;
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
