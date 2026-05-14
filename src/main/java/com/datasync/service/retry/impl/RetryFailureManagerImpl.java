package com.datasync.service.retry.impl;

import com.datasync.model.RetryFailureDetail;
import com.datasync.service.retry.RetryFailureManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class RetryFailureManagerImpl implements RetryFailureManager {

    private static final Logger logger = LoggerFactory.getLogger(RetryFailureManagerImpl.class);

    public static final String REDIS_KEY_PREFIX_FAILURE = "retry_failure:";

    private final Map<String, RetryFailureDetail> failureCache = new ConcurrentHashMap<>();

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @PostConstruct
    public void init() {
        loadAllFailuresFromPersistence();
    }

    @Override
    public void loadAllFailuresFromPersistence() {
        try {
            Set<String> keys = redisTemplate.keys(REDIS_KEY_PREFIX_FAILURE + "*");
            if (keys != null && !keys.isEmpty()) {
                int loaded = 0;
                for (String key : keys) {
                    try {
                        String json = redisTemplate.opsForValue().get(key);
                        if (json != null) {
                            RetryFailureDetail detail = objectMapper.readValue(json, RetryFailureDetail.class);
                            failureCache.put(detail.getDetailId(), detail);
                            loaded++;
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to load failure detail from Redis: {}", key, e);
                    }
                }
                logger.info("Loaded {} retry failure details from persistence", loaded);
            }
        } catch (Exception e) {
            logger.warn("Failed to load failure details from Redis", e);
        }
    }

    @Override
    public RetryFailureDetail recordFailure(RetryFailureDetail detail) {
        if (detail.getDetailId() == null) {
            detail.setDetailId("rf_" + UUID.randomUUID().toString().substring(0, 12));
        }
        if (detail.getFailedAt() == null) {
            detail.setFailedAt(Instant.now());
        }

        failureCache.put(detail.getDetailId(), detail);
        persistFailure(detail);

        logger.error("Recorded retry failure: {} (task: {}, sync: {}, type: {})",
                detail.getDetailId(), detail.getTaskId(), detail.getSyncId(), detail.getFailureType());

        return detail;
    }

    @Override
    public RetryFailureDetail recordFailure(
            String taskId, String syncId, String retryId,
            int attempt, Throwable exception,
            String dataKey, Map<String, Object> dataSnapshot) {

        RetryFailureDetail detail = RetryFailureDetail.fromException(
                taskId, syncId, retryId, attempt, exception, dataKey, dataSnapshot);

        return recordFailure(detail);
    }

    @Override
    public Optional<RetryFailureDetail> getFailureDetail(String detailId) {
        RetryFailureDetail cached = failureCache.get(detailId);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            String json = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX_FAILURE + detailId);
            if (json != null) {
                RetryFailureDetail detail = objectMapper.readValue(json, RetryFailureDetail.class);
                failureCache.put(detailId, detail);
                return Optional.of(detail);
            }
        } catch (Exception e) {
            logger.warn("Failed to get failure detail from Redis: {}", detailId, e);
        }
        return Optional.empty();
    }

    @Override
    public List<RetryFailureDetail> getFailuresByTask(String taskId) {
        return failureCache.values().stream()
                .filter(d -> taskId.equals(d.getTaskId()))
                .sorted(Comparator.comparing(RetryFailureDetail::getFailedAt).reversed())
                .collect(Collectors.toList());
    }

    @Override
    public List<RetryFailureDetail> getFailuresBySync(String syncId) {
        return failureCache.values().stream()
                .filter(d -> syncId.equals(d.getSyncId()))
                .sorted(Comparator.comparing(RetryFailureDetail::getFailedAt).reversed())
                .collect(Collectors.toList());
    }

    @Override
    public List<RetryFailureDetail> getFailuresByRetry(String retryId) {
        return failureCache.values().stream()
                .filter(d -> retryId.equals(d.getRetryId()))
                .sorted(Comparator.comparing(RetryFailureDetail::getFailedAt).reversed())
                .collect(Collectors.toList());
    }

    @Override
    public List<RetryFailureDetail> getFailuresByType(String failureType) {
        return failureCache.values().stream()
                .filter(d -> failureType.equals(d.getFailureType()))
                .sorted(Comparator.comparing(RetryFailureDetail::getFailedAt).reversed())
                .collect(Collectors.toList());
    }

    @Override
    public List<RetryFailureDetail> getUnresolvedFailures() {
        return failureCache.values().stream()
                .filter(d -> "unresolved".equals(d.getResolutionStatus()))
                .sorted(Comparator.comparing(RetryFailureDetail::getFailedAt).reversed())
                .collect(Collectors.toList());
    }

    @Override
    public List<RetryFailureDetail> getFailuresByTimeRange(Instant startTime, Instant endTime) {
        return failureCache.values().stream()
                .filter(d -> {
                    Instant failedAt = d.getFailedAt();
                    if (failedAt == null) {
                        return false;
                    }
                    boolean afterStart = !failedAt.isBefore(startTime);
                    boolean beforeEnd = endTime == null || !failedAt.isAfter(endTime);
                    return afterStart && beforeEnd;
                })
                .sorted(Comparator.comparing(RetryFailureDetail::getFailedAt).reversed())
                .collect(Collectors.toList());
    }

    @Override
    public List<RetryFailureDetail> getAllFailures() {
        return failureCache.values().stream()
                .sorted(Comparator.comparing(RetryFailureDetail::getFailedAt).reversed())
                .collect(Collectors.toList());
    }

    @Override
    public RetryFailureDetail markResolved(String detailId, String notes) {
        RetryFailureDetail detail = failureCache.get(detailId);
        if (detail == null) {
            Optional<RetryFailureDetail> opt = getFailureDetail(detailId);
            if (opt.isPresent()) {
                detail = opt.get();
            } else {
                throw new NoSuchElementException("Failure detail not found: " + detailId);
            }
        }
        detail.markResolved(notes);
        persistFailure(detail);
        logger.info("Marked failure detail as resolved: {}", detailId);
        return detail;
    }

    @Override
    public RetryFailureDetail markIgnored(String detailId, String notes) {
        RetryFailureDetail detail = failureCache.get(detailId);
        if (detail == null) {
            Optional<RetryFailureDetail> opt = getFailureDetail(detailId);
            if (opt.isPresent()) {
                detail = opt.get();
            } else {
                throw new NoSuchElementException("Failure detail not found: " + detailId);
            }
        }
        detail.markIgnored(notes);
        persistFailure(detail);
        logger.info("Marked failure detail as ignored: {}", detailId);
        return detail;
    }

    @Override
    public int getFailureCountByTask(String taskId) {
        return (int) failureCache.values().stream()
                .filter(d -> taskId.equals(d.getTaskId()))
                .count();
    }

    @Override
    public int getFailureCountByType(String failureType) {
        return (int) failureCache.values().stream()
                .filter(d -> failureType.equals(d.getFailureType()))
                .count();
    }

    @Override
    public int getUnresolvedFailureCount() {
        return (int) failureCache.values().stream()
                .filter(d -> "unresolved".equals(d.getResolutionStatus()))
                .count();
    }

    @Override
    public Map<String, Integer> getFailureTypeStatistics(String taskId) {
        Map<String, Integer> stats = new LinkedHashMap<>();

        List<RetryFailureDetail> failures = taskId != null
                ? getFailuresByTask(taskId)
                : getAllFailures();

        for (RetryFailureDetail detail : failures) {
            String type = detail.getFailureType() != null ? detail.getFailureType() : "unknown";
            stats.merge(type, 1, Integer::sum);
        }

        return stats;
    }

    @Override
    public Map<String, Object> getFailureStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();

        stats.put("totalFailures", failureCache.size());
        stats.put("unresolvedFailures", getUnresolvedFailureCount());
        stats.put("resolvedFailures", (int) failureCache.values().stream()
                .filter(d -> "resolved".equals(d.getResolutionStatus())).count());
        stats.put("ignoredFailures", (int) failureCache.values().stream()
                .filter(d -> "ignored".equals(d.getResolutionStatus())).count());

        stats.put("byType", getFailureTypeStatistics(null));

        Set<String> affectedTasks = failureCache.values().stream()
                .map(RetryFailureDetail::getTaskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        stats.put("affectedTasks", affectedTasks.size());

        if (!failureCache.isEmpty()) {
            RetryFailureDetail latest = failureCache.values().stream()
                    .max(Comparator.comparing(RetryFailureDetail::getFailedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                    .orElse(null);
            if (latest != null) {
                stats.put("latestFailureAt", latest.getFailedAt());
                stats.put("latestFailureTask", latest.getTaskId());
                stats.put("latestFailureType", latest.getFailureType());
            }
        }

        return stats;
    }

    private void persistFailure(RetryFailureDetail detail) {
        try {
            String json = objectMapper.writeValueAsString(detail);
            redisTemplate.opsForValue().set(REDIS_KEY_PREFIX_FAILURE + detail.getDetailId(), json);
            logger.debug("Persisted failure detail: {}", detail.getDetailId());
        } catch (Exception e) {
            logger.warn("Failed to persist failure detail to Redis: {}", detail.getDetailId(), e);
        }
    }
}
