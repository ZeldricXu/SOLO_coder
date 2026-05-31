package com.edgescheduler.modules.aggregation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edgescheduler.common.exception.BusinessException;
import com.edgescheduler.common.util.IdGenerator;
import com.edgescheduler.modules.aggregation.domain.AggregationCheckpoint;
import com.edgescheduler.modules.aggregation.domain.DataAggregation;
import com.edgescheduler.modules.aggregation.mapper.AggregationCheckpointMapper;
import com.edgescheduler.modules.aggregation.mapper.DataAggregationMapper;
import com.alibaba.fastjson2.JSON;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import javax.annotation.PreDestroy;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataAggregationService {

    private final DataAggregationMapper dataAggregationMapper;
    private final AggregationCheckpointMapper checkpointMapper;
    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final MeterRegistry meterRegistry;

    private final Map<String, List<Map<String, Object>>> dataBuffer = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> windowStart = new ConcurrentHashMap<>();
    private final Map<String, String> bufferCheckpoints = new ConcurrentHashMap<>();
    private final Map<String, Object> bufferLocks = new ConcurrentHashMap<>();
    private final AtomicBoolean recoveryMode = new AtomicBoolean(false);
    private final Set<String> deadLetterQueue = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final ExecutorService checkpointExecutor = new ThreadPoolExecutor(
            1, 4, 60L, TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            new ThreadFactory() {
                private final ThreadFactory delegate = Executors.defaultThreadFactory();
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = delegate.newThread(r);
                    t.setName("agg-checkpoint-" + t.getName());
                    t.setDaemon(true);
                    return t;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    private static final int MAX_RETRY_ATTEMPTS = 5;
    private static final long CHECKPOINT_INTERVAL_MS = 30000;
    private static final long MAX_BUFFER_SIZE = 10000;
    private static final int MAX_DEVICE_ID_LENGTH = 128;
    private static final int MAX_AGGREGATION_TYPE_LENGTH = 64;
    private static final int MAX_TIME_WINDOW_LENGTH = 16;
    private static final int MAX_DATA_POINT_SIZE = 1024 * 1024;
    private static final String CHECKPOINT_REDIS_PREFIX = "agg:checkpoint:";
    private static final String DLQ_REDIS_KEY = "agg:dlq";

    @javax.annotation.PostConstruct
    public void init() {
        attemptRecovery();
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down aggregation service, saving final checkpoints...");
        try {
            for (String key : new ArrayList<>(dataBuffer.keySet())) {
                try {
                    saveCheckpoint(key, true);
                } catch (Exception e) {
                    log.warn("Failed to save checkpoint during shutdown for {}: {}", key, e.getMessage());
                }
            }
        } finally {
            checkpointExecutor.shutdown();
            try {
                if (!checkpointExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    checkpointExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                checkpointExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("Aggregation service shutdown complete");
    }

    private void validateInput(String deviceId, String aggregationType, String timeWindow) {
        if (deviceId == null || deviceId.trim().isEmpty()) {
            throw new IllegalArgumentException("Device ID cannot be null or empty");
        }
        if (deviceId.length() > MAX_DEVICE_ID_LENGTH) {
            throw new IllegalArgumentException("Device ID exceeds maximum length of " + MAX_DEVICE_ID_LENGTH);
        }
        if (aggregationType == null || aggregationType.trim().isEmpty()) {
            throw new IllegalArgumentException("Aggregation type cannot be null or empty");
        }
        if (aggregationType.length() > MAX_AGGREGATION_TYPE_LENGTH) {
            throw new IllegalArgumentException("Aggregation type exceeds maximum length of " + MAX_AGGREGATION_TYPE_LENGTH);
        }
        if (timeWindow == null || timeWindow.trim().isEmpty()) {
            throw new IllegalArgumentException("Time window cannot be null or empty");
        }
        if (timeWindow.length() > MAX_TIME_WINDOW_LENGTH) {
            throw new IllegalArgumentException("Time window exceeds maximum length of " + MAX_TIME_WINDOW_LENGTH);
        }
    }

    private Object getBufferLock(String key) {
        return bufferLocks.computeIfAbsent(key, k -> new Object());
    }

    public Mono<DataAggregation> aggregateData(String deviceId, String aggregationType,
                                                String timeWindow, Map<String, Object> dataPoint) {
        validateInput(deviceId, aggregationType, timeWindow);
        String key = deviceId + ":" + aggregationType + ":" + timeWindow;

        try {
            validateDataPoint(dataPoint);

            Object lock = getBufferLock(key);
            synchronized (lock) {
                dataBuffer.computeIfAbsent(key, k -> {
                    windowStart.put(k, LocalDateTime.now());
                    return Collections.synchronizedList(new ArrayList<>());
                });

                List<Map<String, Object>> buffer = dataBuffer.get(key);
                buffer.add(dataPoint);

                if (buffer.size() % 100 == 0) {
                    saveCheckpointAsync(key);
                }

                Duration windowDuration = parseTimeWindow(timeWindow);
                LocalDateTime now = LocalDateTime.now();
                LocalDateTime start = windowStart.get(key);

                if (Duration.between(start, now).compareTo(windowDuration) >= 0) {
                    return performAggregation(deviceId, aggregationType, timeWindow, key);
                }

                if (buffer.size() >= MAX_BUFFER_SIZE) {
                    log.warn("Buffer for {} reached max size {}, performing early aggregation", key, MAX_BUFFER_SIZE);
                    return performAggregation(deviceId, aggregationType, timeWindow, key);
                }
            }

            return Mono.just(createPendingAggregation(deviceId, aggregationType, timeWindow));

        } catch (Exception e) {
            log.error("Error processing data point for {}: {}", key, e.getMessage());
            handleDataPointFailure(key, dataPoint, e);
            return Mono.error(e);
        }
    }

    public Mono<DataAggregation> forceAggregation(String deviceId, String aggregationType, String timeWindow) {
        String key = deviceId + ":" + aggregationType + ":" + timeWindow;
        if (!dataBuffer.containsKey(key) || dataBuffer.get(key).isEmpty()) {
            return Mono.error(new BusinessException("没有待聚合的数据"));
        }
        return performAggregation(deviceId, aggregationType, timeWindow, key);
    }

    private Mono<DataAggregation> performAggregation(String deviceId, String aggregationType,
                                                      String timeWindow, String key) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String checkpointId = bufferCheckpoints.get(key);

        try {
            List<Map<String, Object>> dataPoints = new ArrayList<>(dataBuffer.getOrDefault(key, Collections.emptyList()));
            LocalDateTime start = windowStart.getOrDefault(key, LocalDateTime.now());
            LocalDateTime end = LocalDateTime.now();

            if (dataPoints.isEmpty()) {
                return Mono.just(createEmptyAggregation(deviceId, aggregationType, timeWindow, start, end));
            }

            String checksum = calculateDataChecksum(dataPoints);
            Map<String, Object> aggregatedData = calculateAggregation(dataPoints, aggregationType);

            DataAggregation aggregation = new DataAggregation();
            aggregation.setAggregationId(IdGenerator.generateId("agg"));
            aggregation.setDeviceId(deviceId);
            aggregation.setAggregationType(aggregationType);
            aggregation.setTimeWindow(timeWindow);
            aggregation.setDataPointsCount(dataPoints.size());
            aggregation.setAggregatedData(aggregatedData);
            aggregation.setStartTime(start);
            aggregation.setEndTime(end);
            aggregation.setUploadStatus("PENDING");
            aggregation.setCheckpointId(checkpointId);
            aggregation.setDataChecksum(checksum);
            aggregation.setRecoveryStatus(recoveryMode.get() ? "RECOVERED" : "NONE");
            aggregation.setFailureCount(0);

            dataAggregationMapper.insert(aggregation);

            dataBuffer.remove(key);
            windowStart.remove(key);
            bufferCheckpoints.remove(key);
            deleteCheckpoint(key);

            updateMetrics(aggregationType, dataPoints.size(), "success");
            recoveryMode.set(false);

            return Mono.just(aggregation);

        } catch (Exception e) {
            log.error("Aggregation failed for {}: {}", key, e.getMessage());
            handleAggregationFailure(key, deviceId, aggregationType, timeWindow, e, checkpointId);
            updateMetrics(aggregationType, 0, "failed");
            return Mono.error(e);
        } finally {
            sample.stop(Timer.builder("edge_scheduler_aggregation_duration")
                    .description("Duration of aggregation operation")
                    .tag("type", aggregationType)
                    .register(meterRegistry));
        }
    }

    private void validateDataPoint(Map<String, Object> dataPoint) {
        if (dataPoint == null || dataPoint.isEmpty()) {
            throw new IllegalArgumentException("Data point cannot be null or empty");
        }
        if (dataPoint.size() > 1000) {
            throw new IllegalArgumentException("Data point exceeds maximum field count of 1000");
        }
        String jsonStr = JSON.toJSONString(dataPoint);
        if (jsonStr.length() > MAX_DATA_POINT_SIZE) {
            throw new IllegalArgumentException("Data point exceeds maximum size of " + MAX_DATA_POINT_SIZE + " bytes");
        }
        boolean hasValidValue = dataPoint.values().stream()
                .anyMatch(v -> v instanceof Number || (v instanceof String && !((String) v).isEmpty()));
        if (!hasValidValue) {
            throw new IllegalArgumentException("Data point must contain at least one numeric or non-empty string value");
        }
    }

    private void handleDataPointFailure(String key, Map<String, Object> dataPoint, Exception e) {
        Counter.builder("edge_scheduler_aggregation_data_point_failures")
                .description("Failed data point count")
                .tag("key", key)
                .register(meterRegistry)
                .increment();

        Map<String, Object> dlqEntry = new HashMap<>();
        dlqEntry.put("key", key);
        dlqEntry.put("dataPoint", dataPoint);
        dlqEntry.put("error", e.getMessage() != null ? e.getMessage() : "Unknown error");
        dlqEntry.put("timestamp", System.currentTimeMillis());
        dlqEntry.put("retryCount", 0);

        redisTemplate.opsForList().rightPush(DLQ_REDIS_KEY, JSON.toJSONString(dlqEntry))
                .doOnError(err -> log.warn("Failed to push data point to DLQ for {}: {}", key, err.getMessage()))
                .onErrorResume(err -> Mono.empty())
                .subscribe();
    }

    private void handleAggregationFailure(String key, String deviceId, String aggregationType,
                                           String timeWindow, Exception e, String checkpointId) {
        DataAggregation failedAggregation = new DataAggregation();
        failedAggregation.setAggregationId(IdGenerator.generateId("agg"));
        failedAggregation.setDeviceId(deviceId);
        failedAggregation.setAggregationType(aggregationType);
        failedAggregation.setTimeWindow(timeWindow);
        failedAggregation.setDataPointsCount(dataBuffer.getOrDefault(key, Collections.emptyList()).size());
        failedAggregation.setAggregatedData(Collections.singletonMap("error", e.getMessage()));
        failedAggregation.setStartTime(windowStart.get(key));
        failedAggregation.setEndTime(LocalDateTime.now());
        failedAggregation.setUploadStatus("FAILED");
        failedAggregation.setCheckpointId(checkpointId);
        failedAggregation.setFailureCount(1);
        failedAggregation.setLastFailureTime(LocalDateTime.now());
        failedAggregation.setLastFailureReason(e.getMessage());
        failedAggregation.setRecoveryStatus("FAILED");

        dataAggregationMapper.insert(failedAggregation);

        if (dataBuffer.containsKey(key)) {
            saveCheckpoint(key, true);
        }

        Counter.builder("edge_scheduler_aggregation_failures")
                .description("Aggregation failure count")
                .tag("type", aggregationType)
                .tag("deviceId", deviceId)
                .register(meterRegistry)
                .increment();
    }

    @Scheduled(fixedRateString = "${edgescheduler.aggregation.checkpoint-interval:30000}")
    @Transactional(rollbackFor = Exception.class)
    public void saveCheckpoints() {
        if (dataBuffer.isEmpty()) {
            return;
        }

        log.debug("Saving checkpoints for {} buffers", dataBuffer.size());
        for (String key : dataBuffer.keySet()) {
            saveCheckpoint(key, false);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveCheckpoint(String key, boolean isEmergency) {
        List<Map<String, Object>> buffer = dataBuffer.get(key);
        if (buffer == null || buffer.isEmpty()) {
            return;
        }

        try {
            String[] parts = key.split(":");
            if (parts.length < 3) {
                return;
            }

            String checkpointId = IdGenerator.generateId("cp");
            AggregationCheckpoint checkpoint = new AggregationCheckpoint();
            checkpoint.setCheckpointId(checkpointId);
            checkpoint.setDeviceId(parts[0]);
            checkpoint.setAggregationType(parts[1]);
            checkpoint.setTimeWindow(parts[2]);
            checkpoint.setBufferSnapshot(new ArrayList<>(buffer));
            checkpoint.setWindowStart(windowStart.get(key));

            checkpointMapper.insert(checkpoint);
            bufferCheckpoints.put(key, checkpointId);

            redisTemplate.opsForValue().set(
                    CHECKPOINT_REDIS_PREFIX + key,
                    checkpointId,
                    Duration.ofHours(24)
            ).subscribe();

            log.debug("Checkpoint saved for {}: {} (emergency: {})", key, checkpointId, isEmergency);

            Counter.builder("edge_scheduler_aggregation_checkpoints_saved")
                    .description("Checkpoints saved count")
                    .tag("emergency", String.valueOf(isEmergency))
                    .register(meterRegistry)
                    .increment();

        } catch (Exception e) {
            log.error("Failed to save checkpoint for {}: {}", key, e.getMessage());
        }
    }

    private void saveCheckpointAsync(String key) {
        try {
            checkpointExecutor.submit(() -> {
                try {
                    saveCheckpoint(key, false);
                } catch (Exception e) {
                    log.error("Async checkpoint save failed for {}: {}", key, e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("Failed to submit async checkpoint task for {}: {}", key, e.getMessage());
        }
    }

    private void deleteCheckpoint(String key) {
        String checkpointId = bufferCheckpoints.get(key);
        if (checkpointId != null) {
            try {
                checkpointMapper.delete(
                        new LambdaQueryWrapper<AggregationCheckpoint>()
                                .eq(AggregationCheckpoint::getCheckpointId, checkpointId));
            } catch (Exception e) {
                log.warn("Failed to delete checkpoint from DB for {}: {}", key, e.getMessage());
            }
        }
        redisTemplate.delete(CHECKPOINT_REDIS_PREFIX + key)
                .doOnError(e -> log.warn("Failed to delete checkpoint from Redis for {}: {}", key, e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }

    @Transactional(rollbackFor = Exception.class)
    public Mono<Map<String, Object>> attemptRecovery() {
        Map<String, Object> recoveryResult = new HashMap<>();
        List<String> recoveredKeys = new ArrayList<>();

        try {
            log.info("Starting aggregation recovery process...");

            List<AggregationCheckpoint> checkpoints = checkpointMapper.selectList(
                    new LambdaQueryWrapper<AggregationCheckpoint>()
                            .orderByAsc(AggregationCheckpoint::getCreatedAt));

            for (AggregationCheckpoint checkpoint : checkpoints) {
                try {
                    String key = checkpoint.getDeviceId() + ":" + checkpoint.getAggregationType() + ":" + checkpoint.getTimeWindow();

                    dataBuffer.put(key, new ArrayList<>(checkpoint.getBufferSnapshot()));
                    windowStart.put(key, checkpoint.getWindowStart());
                    bufferCheckpoints.put(key, checkpoint.getCheckpointId());

                    recoveredKeys.add(key);
                    log.info("Recovered buffer for {} with {} data points", 
                            key, checkpoint.getBufferSnapshot().size());

                } catch (Exception e) {
                    log.error("Failed to recover checkpoint {}: {}", checkpoint.getCheckpointId(), e.getMessage());
                    moveCheckpointToDLQ(checkpoint);
                }
            }

            recoveryResult.put("success", true);
            recoveryResult.put("recoveredCount", recoveredKeys.size());
            recoveryResult.put("recoveredKeys", recoveredKeys);

            if (!recoveredKeys.isEmpty()) {
                recoveryMode.set(true);
                processRecoveredBuffers();
            }

            Counter.builder("edge_scheduler_aggregation_recoveries")
                    .description("Recovery operations count")
                    .tag("recoveredCount", String.valueOf(recoveredKeys.size()))
                    .register(meterRegistry)
                    .increment();

            log.info("Recovery completed. Recovered {} buffers", recoveredKeys.size());

        } catch (Exception e) {
            log.error("Recovery process failed: {}", e.getMessage());
            recoveryResult.put("success", false);
            recoveryResult.put("error", e.getMessage());
        }

        return Mono.just(recoveryResult);
    }

    private void processRecoveredBuffers() {
        for (String key : new ArrayList<>(dataBuffer.keySet())) {
            try {
                String[] parts = key.split(":");
                if (parts.length >= 3) {
                    performAggregation(parts[0], parts[1], parts[2], key).subscribe();
                }
            } catch (Exception e) {
                log.error("Failed to process recovered buffer {}: {}", key, e.getMessage());
            }
        }
    }

    private void moveCheckpointToDLQ(AggregationCheckpoint checkpoint) {
        Map<String, Object> dlqEntry = new HashMap<>();
        dlqEntry.put("checkpointId", checkpoint.getCheckpointId());
        dlqEntry.put("deviceId", checkpoint.getDeviceId());
        dlqEntry.put("aggregationType", checkpoint.getAggregationType());
        dlqEntry.put("timeWindow", checkpoint.getTimeWindow());
        dlqEntry.put("bufferSize", checkpoint.getBufferSnapshot() != null ? checkpoint.getBufferSnapshot().size() : 0);
        dlqEntry.put("timestamp", System.currentTimeMillis());
        dlqEntry.put("retryCount", 0);

        redisTemplate.opsForList().rightPush(DLQ_REDIS_KEY + ":checkpoints", JSON.toJSONString(dlqEntry))
                .doOnError(e -> log.warn("Failed to push checkpoint to DLQ: {}", e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .subscribe();

        try {
            checkpointMapper.deleteById(checkpoint.getId());
        } catch (Exception e) {
            log.warn("Failed to delete checkpoint from DB: {}", e.getMessage());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Mono<DataAggregation> retryFailedAggregation(String aggregationId) {
        DataAggregation failedAggregation = dataAggregationMapper.selectOne(
                new LambdaQueryWrapper<DataAggregation>()
                        .eq(DataAggregation::getAggregationId, aggregationId));

        if (failedAggregation == null) {
            return Mono.error(new BusinessException("聚合记录不存在"));
        }

        if (!"FAILED".equals(failedAggregation.getUploadStatus())) {
            return Mono.error(new BusinessException("该聚合记录不处于失败状态"));
        }

        if (failedAggregation.getFailureCount() >= MAX_RETRY_ATTEMPTS) {
            moveToDLQ(failedAggregation);
            return Mono.error(new BusinessException("已达到最大重试次数，已移至死信队列"));
        }

        String key = failedAggregation.getDeviceId() + ":" + 
                      failedAggregation.getAggregationType() + ":" + 
                      failedAggregation.getTimeWindow();

        if (failedAggregation.getCheckpointId() != null) {
            AggregationCheckpoint checkpoint = checkpointMapper.selectOne(
                    new LambdaQueryWrapper<AggregationCheckpoint>()
                            .eq(AggregationCheckpoint::getCheckpointId, failedAggregation.getCheckpointId()));

            if (checkpoint != null) {
                dataBuffer.put(key, new ArrayList<>(checkpoint.getBufferSnapshot()));
                windowStart.put(key, checkpoint.getWindowStart());
                bufferCheckpoints.put(key, checkpoint.getCheckpointId());
            }
        }

        failedAggregation.setFailureCount(failedAggregation.getFailureCount() + 1);
        failedAggregation.setLastFailureTime(LocalDateTime.now());
        dataAggregationMapper.updateById(failedAggregation);

        return performAggregation(
                failedAggregation.getDeviceId(),
                failedAggregation.getAggregationType(),
                failedAggregation.getTimeWindow(),
                key
        );
    }

    private void moveToDLQ(DataAggregation aggregation) {
        Map<String, Object> dlqEntry = new HashMap<>();
        dlqEntry.put("aggregationId", aggregation.getAggregationId());
        dlqEntry.put("deviceId", aggregation.getDeviceId());
        dlqEntry.put("aggregationType", aggregation.getAggregationType());
        dlqEntry.put("failureReason", aggregation.getLastFailureReason());
        dlqEntry.put("failureCount", aggregation.getFailureCount());
        dlqEntry.put("timestamp", System.currentTimeMillis());

        redisTemplate.opsForList().rightPush(DLQ_REDIS_KEY + ":aggregations", JSON.toJSONString(dlqEntry)).subscribe();

        aggregation.setUploadStatus("DEAD_LETTER");
        dataAggregationMapper.updateById(aggregation);

        log.warn("Moved aggregation {} to dead letter queue", aggregation.getAggregationId());
    }

    public Mono<List<Map<String, Object>>> getDeadLetterQueue(String type) {
        String key = DLQ_REDIS_KEY + (type != null ? ":" + type : "");
        return redisTemplate.opsForList().range(key, 0, -1)
                .map(items -> {
                    List<Map<String, Object>> result = new ArrayList<>();
                    for (Object item : items) {
                        try {
                            result.add(JSON.parseObject(item.toString(), Map.class));
                        } catch (Exception e) {
                            result.add(Collections.singletonMap("raw", item));
                        }
                    }
                    return result;
                });
    }

    @Transactional(rollbackFor = Exception.class)
    public Mono<Boolean> clearDeadLetterQueue(String type) {
        String key = DLQ_REDIS_KEY + (type != null ? ":" + type : "");
        return redisTemplate.delete(key)
                .map(deleted -> deleted > 0);
    }

    @Scheduled(cron = "0 */5 * * * ?")
    @Transactional(rollbackFor = Exception.class)
    public void retryFailedAggregations() {
        List<DataAggregation> failedAggregations = dataAggregationMapper.selectList(
                new LambdaQueryWrapper<DataAggregation>()
                        .eq(DataAggregation::getUploadStatus, "FAILED")
                        .lt(DataAggregation::getFailureCount, MAX_RETRY_ATTEMPTS)
                        .last("LIMIT 10"));

        for (DataAggregation agg : failedAggregations) {
            try {
                retryFailedAggregation(agg.getAggregationId()).subscribe();
            } catch (Exception e) {
                log.error("Auto-retry failed for aggregation {}: {}", agg.getAggregationId(), e.getMessage());
            }
        }
    }

    private String calculateDataChecksum(List<Map<String, Object>> dataPoints) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String dataStr = JSON.toJSONString(dataPoints);
            byte[] hash = digest.digest(dataStr.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString().substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(dataPoints.hashCode());
        }
    }

    private Map<String, Object> calculateAggregation(List<Map<String, Object>> dataPoints, String aggregationType) {
        Map<String, Object> result = new HashMap<>();

        Set<String> keys = new HashSet<>();
        dataPoints.forEach(point -> keys.addAll(point.keySet()));

        for (String key : keys) {
            List<Double> values = new ArrayList<>();
            for (Map<String, Object> point : dataPoints) {
                Object value = point.get(key);
                if (value instanceof Number) {
                    values.add(((Number) value).doubleValue());
                }
            }

            if (!values.isEmpty()) {
                switch (aggregationType.toUpperCase()) {
                    case "AVG":
                        result.put(key + "_avg", calculateAverage(values));
                        break;
                    case "SUM":
                        result.put(key + "_sum", calculateSum(values));
                        break;
                    case "MAX":
                        result.put(key + "_max", Collections.max(values));
                        break;
                    case "MIN":
                        result.put(key + "_min", Collections.min(values));
                        break;
                    case "COUNT":
                        result.put(key + "_count", values.size());
                        break;
                    case "FIRST":
                        result.put(key + "_first", values.get(0));
                        break;
                    case "LAST":
                        result.put(key + "_last", values.get(values.size() - 1));
                        break;
                    case "MEDIAN":
                        result.put(key + "_median", calculateMedian(values));
                        break;
                    case "ALL":
                        result.put(key + "_avg", calculateAverage(values));
                        result.put(key + "_sum", calculateSum(values));
                        result.put(key + "_max", Collections.max(values));
                        result.put(key + "_min", Collections.min(values));
                        result.put(key + "_count", values.size());
                        break;
                    default:
                        result.put(key + "_avg", calculateAverage(values));
                }
            }
        }

        result.put("aggregation_type", aggregationType);
        result.put("data_points", dataPoints.size());
        result.put("checksum", calculateDataChecksum(dataPoints));
        return result;
    }

    private double calculateAverage(List<Double> values) {
        if (values.isEmpty()) return 0.0;
        double sum = values.stream().mapToDouble(Double::doubleValue).sum();
        return BigDecimal.valueOf(sum / values.size())
                .setScale(4, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private double calculateSum(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).sum();
    }

    private double calculateMedian(List<Double> values) {
        Collections.sort(values);
        int middle = values.size() / 2;
        if (values.size() % 2 == 0) {
            return (values.get(middle - 1) + values.get(middle)) / 2.0;
        } else {
            return values.get(middle);
        }
    }

    private Duration parseTimeWindow(String timeWindow) {
        if (timeWindow == null || timeWindow.isEmpty()) {
            return Duration.ofMinutes(5);
        }
        if (timeWindow.length() < 2) {
            throw new IllegalArgumentException("Invalid time window format: " + timeWindow);
        }
        char unit = timeWindow.charAt(timeWindow.length() - 1);
        String numericPart = timeWindow.substring(0, timeWindow.length() - 1);
        if (numericPart.isEmpty() || numericPart.length() > 9) {
            throw new IllegalArgumentException("Invalid time window value: " + timeWindow);
        }
        int value;
        try {
            value = Integer.parseInt(numericPart);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid time window format: " + timeWindow, e);
        }
        if (value <= 0 || value > 86400) {
            throw new IllegalArgumentException("Time window value must be between 1 and 86400: " + value);
        }
        return switch (unit) {
            case 's', 'S' -> Duration.ofSeconds(value);
            case 'm', 'M' -> Duration.ofMinutes(value);
            case 'h', 'H' -> Duration.ofHours(value);
            case 'd', 'D' -> Duration.ofDays(value);
            default -> throw new IllegalArgumentException("Unsupported time unit: " + unit);
        };
    }

    private DataAggregation createPendingAggregation(String deviceId, String aggregationType, String timeWindow) {
        DataAggregation aggregation = new DataAggregation();
        aggregation.setDeviceId(deviceId);
        aggregation.setAggregationType(aggregationType);
        aggregation.setTimeWindow(timeWindow);
        aggregation.setDataPointsCount(dataBuffer.get(deviceId + ":" + aggregationType + ":" + timeWindow).size());
        aggregation.setUploadStatus("BUFFERING");
        aggregation.setCheckpointId(bufferCheckpoints.get(deviceId + ":" + aggregationType + ":" + timeWindow));
        return aggregation;
    }

    private DataAggregation createEmptyAggregation(String deviceId, String aggregationType,
                                                    String timeWindow, LocalDateTime start, LocalDateTime end) {
        DataAggregation aggregation = new DataAggregation();
        aggregation.setAggregationId(IdGenerator.generateId("agg"));
        aggregation.setDeviceId(deviceId);
        aggregation.setAggregationType(aggregationType);
        aggregation.setTimeWindow(timeWindow);
        aggregation.setDataPointsCount(0);
        aggregation.setAggregatedData(Collections.singletonMap("message", "no_data"));
        aggregation.setStartTime(start);
        aggregation.setEndTime(end);
        aggregation.setUploadStatus("SKIPPED");
        aggregation.setRecoveryStatus(recoveryMode.get() ? "RECOVERED" : "NONE");
        return aggregation;
    }

    @Transactional(rollbackFor = Exception.class)
    public Mono<DataAggregation> markAsUploaded(String aggregationId) {
        DataAggregation aggregation = dataAggregationMapper.selectOne(
                new LambdaQueryWrapper<DataAggregation>()
                        .eq(DataAggregation::getAggregationId, aggregationId));
        if (aggregation == null) {
            return Mono.error(new BusinessException("聚合记录不存在"));
        }

        aggregation.setUploadStatus("UPLOADED");
        aggregation.setUploadTime(LocalDateTime.now());
        dataAggregationMapper.updateById(aggregation);

        return Mono.just(aggregation);
    }

    public Flux<DataAggregation> getPendingUploads(String deviceId) {
        List<DataAggregation> aggregations = dataAggregationMapper.selectList(
                new LambdaQueryWrapper<DataAggregation>()
                        .eq(deviceId != null, DataAggregation::getDeviceId, deviceId)
                        .in(DataAggregation::getUploadStatus, "PENDING", "FAILED")
                        .orderByAsc(DataAggregation::getStartTime));
        return Flux.fromIterable(aggregations);
    }

    public Flux<DataAggregation> getFailedAggregations(String deviceId) {
        List<DataAggregation> aggregations = dataAggregationMapper.selectList(
                new LambdaQueryWrapper<DataAggregation>()
                        .eq(deviceId != null, DataAggregation::getDeviceId, deviceId)
                        .eq(DataAggregation::getUploadStatus, "FAILED")
                        .orderByDesc(DataAggregation::getLastFailureTime));
        return Flux.fromIterable(aggregations);
    }

    public Mono<Map<String, Object>> getRecoveryStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("recoveryMode", recoveryMode.get());
        status.put("activeBuffers", dataBuffer.size());
        status.put("totalBufferedPoints", dataBuffer.values().stream().mapToInt(List::size).sum());
        status.put("pendingCheckpoints", bufferCheckpoints.size());

        long failedCount = dataAggregationMapper.selectCount(
                new LambdaQueryWrapper<DataAggregation>()
                        .eq(DataAggregation::getUploadStatus, "FAILED"));
        status.put("failedAggregations", failedCount);

        long dlqCount = dataAggregationMapper.selectCount(
                new LambdaQueryWrapper<DataAggregation>()
                        .eq(DataAggregation::getUploadStatus, "DEAD_LETTER"));
        status.put("deadLetterCount", dlqCount);

        return Mono.just(status);
    }

    private void updateMetrics(String aggregationType, int dataPoints, String status) {
        Counter.builder("edge_scheduler_aggregations_total")
                .description("Total aggregation operations")
                .tag("type", aggregationType)
                .tag("status", status)
                .register(meterRegistry)
                .increment();

        if (dataPoints > 0) {
            Counter.builder("edge_scheduler_aggregated_data_points")
                    .description("Total aggregated data points")
                    .tag("type", aggregationType)
                    .register(meterRegistry)
                    .increment(dataPoints);
        }
    }

    public Mono<Map<String, Object>> getBufferStatus() {
        Map<String, Object> status = new HashMap<>();
        dataBuffer.forEach((key, value) -> {
            Map<String, Object> bufferInfo = new HashMap<>();
            bufferInfo.put("count", value.size());
            bufferInfo.put("windowStart", windowStart.get(key));
            bufferInfo.put("checkpointId", bufferCheckpoints.get(key));
            status.put(key, bufferInfo);
        });
        return Mono.just(status);
    }
}
