package com.meshcontrol.eventstore.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.meshcontrol.common.base.BaseService;
import com.meshcontrol.common.exception.BusinessException;
import com.meshcontrol.common.util.IdGenerator;
import com.meshcontrol.eventstore.dto.EventPublishRequest;
import com.meshcontrol.eventstore.dto.EventQueryRequest;
import com.meshcontrol.eventstore.dto.ProjectionRebuildRequest;
import com.meshcontrol.eventstore.dto.TimetravelQueryRequest;
import com.meshcontrol.eventstore.entity.EventLog;
import com.meshcontrol.eventstore.entity.Snapshot;
import com.meshcontrol.eventstore.mapper.EventLogMapper;
import com.meshcontrol.eventstore.mapper.SnapshotMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventStoreService extends BaseService<EventLogMapper, EventLog> {

    private final EventLogMapper eventLogMapper;
    private final SnapshotMapper snapshotMapper;
    private final ApplicationEventPublisher eventPublisher;

    private final Map<String, ReentrantLock> aggregateLocks = new ConcurrentHashMap<>();
    private static final int LOCK_TIMEOUT_SECONDS = 5;
    private static final int MAX_RETRIES = 3;

    @Transactional
    public EventLog publishEvent(EventPublishRequest request) {
        validatePublishRequest(request);

        String lockKey = request.getAggregateType() + ":" + request.getAggregateId();
        ReentrantLock lock = aggregateLocks.computeIfAbsent(lockKey, k -> new ReentrantLock());

        try {
            if (!lock.tryLock(LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new BusinessException("Failed to acquire lock for aggregate: " + lockKey);
            }

            try {
                return publishEventWithRetry(request, lockKey);
            } finally {
                lock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("Interrupted while acquiring lock for aggregate: " + lockKey);
        }
    }

    private EventLog publishEventWithRetry(EventPublishRequest request, String lockKey) {
        int retryCount = 0;
        while (retryCount < MAX_RETRIES) {
            try {
                Integer currentVersion = eventLogMapper.findMaxVersion(
                        request.getAggregateId(), request.getAggregateType());
                int nextVersion = currentVersion != null ? currentVersion + 1 : 1;

                EventLog eventLog = new EventLog();
                eventLog.setEventId(IdGenerator.generateId("evt"));
                eventLog.setAggregateId(request.getAggregateId());
                eventLog.setAggregateType(request.getAggregateType());
                eventLog.setEventType(request.getEventType());
                eventLog.setVersion(nextVersion);
                eventLog.setPayload(request.getPayload() != null ?
                        Collections.unmodifiableMap(new HashMap<>(request.getPayload())) :
                        Collections.emptyMap());
                eventLog.setMetadata(request.getMetadata() != null ?
                        Collections.unmodifiableMap(new HashMap<>(request.getMetadata())) :
                        Collections.emptyMap());
                eventLog.setSource(request.getSource() != null ? request.getSource() : "api");
                eventLog.setCreatedAt(LocalDateTime.now());

                eventLogMapper.insert(eventLog);
                log.info("Event published: {} for aggregate: {} version: {}",
                        eventLog.getEventId(), eventLog.getAggregateId(), nextVersion);

                eventPublisher.publishEvent(eventLog);
                return eventLog;

            } catch (DuplicateKeyException e) {
                retryCount++;
                if (retryCount >= MAX_RETRIES) {
                    log.error("Failed to publish event after {} retries for aggregate: {}",
                            MAX_RETRIES, lockKey, e);
                    throw new BusinessException("Failed to publish event: version conflict after retries");
                }
                log.warn("Version conflict detected, retrying {}/{} for aggregate: {}",
                        retryCount, MAX_RETRIES, lockKey);
                try {
                    Thread.sleep(100L * retryCount);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new BusinessException("Interrupted during retry");
                }
            }
        }
        throw new BusinessException("Failed to publish event: max retries exceeded");
    }

    private void validatePublishRequest(EventPublishRequest request) {
        if (request.getAggregateId() == null || request.getAggregateId().isBlank()) {
            throw new BusinessException("aggregateId cannot be null or blank");
        }
        if (request.getAggregateType() == null || request.getAggregateType().isBlank()) {
            throw new BusinessException("aggregateType cannot be null or blank");
        }
        if (request.getEventType() == null || request.getEventType().isBlank()) {
            throw new BusinessException("eventType cannot be null or blank");
        }
        if (request.getAggregateId().length() > 128) {
            throw new BusinessException("aggregateId exceeds maximum length of 128");
        }
        if (request.getAggregateType().length() > 64) {
            throw new BusinessException("aggregateType exceeds maximum length of 64");
        }
        if (request.getEventType().length() > 64) {
            throw new BusinessException("eventType exceeds maximum length of 64");
        }
    }

    public IPage<EventLog> queryEvents(EventQueryRequest request) {
        LambdaQueryWrapper<EventLog> wrapper = new LambdaQueryWrapper<>();
        if (request.getAggregateId() != null) {
            wrapper.eq(EventLog::getAggregateId, request.getAggregateId());
        }
        if (request.getAggregateType() != null) {
            wrapper.eq(EventLog::getAggregateType, request.getAggregateType());
        }
        if (request.getEventType() != null) {
            wrapper.eq(EventLog::getEventType, request.getEventType());
        }
        if (request.getStartTime() != null) {
            wrapper.ge(EventLog::getCreatedAt, request.getStartTime());
        }
        if (request.getEndTime() != null) {
            wrapper.le(EventLog::getCreatedAt, request.getEndTime());
        }
        wrapper.orderByDesc(EventLog::getCreatedAt);
        return page(request.getPageNum(), request.getPageSize(), wrapper);
    }

    public List<EventLog> getEventStream(String aggregateId, String aggregateType, Integer sinceVersion) {
        int version = sinceVersion != null ? sinceVersion : 0;
        return eventLogMapper.findByAggregateIdAndVersionGreaterThan(aggregateId, aggregateType, version);
    }

    @Transactional
    public Snapshot createSnapshot(String aggregateId, String aggregateType) {
        if (aggregateId == null || aggregateId.isBlank()) {
            throw new BusinessException("aggregateId cannot be null or blank");
        }
        if (aggregateType == null || aggregateType.isBlank()) {
            throw new BusinessException("aggregateType cannot be null or blank");
        }

        String lockKey = aggregateType + ":" + aggregateId;
        ReentrantLock lock = aggregateLocks.computeIfAbsent(lockKey, k -> new ReentrantLock());

        try {
            if (!lock.tryLock(LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new BusinessException("Failed to acquire lock for aggregate: " + lockKey);
            }

            try {
                Integer currentVersion = eventLogMapper.findMaxVersion(aggregateId, aggregateType);
                if (currentVersion == null) {
                    throw new BusinessException("No events found for aggregate");
                }

                List<EventLog> events = eventLogMapper.findByAggregateIdAndVersionGreaterThan(
                        aggregateId, aggregateType, 0);

                Map<String, Object> state = rebuildState(events);
                Map<String, Object> metrics = calculateMetrics(events);

                Snapshot snapshot = new Snapshot();
                snapshot.setSnapshotId(IdGenerator.generateId("snap"));
                snapshot.setAggregateId(aggregateId);
                snapshot.setAggregateType(aggregateType);
                snapshot.setVersion(currentVersion);
                snapshot.setState(Collections.unmodifiableMap(state));
                snapshot.setMetrics(Collections.unmodifiableMap(metrics));
                snapshot.setTimestamp(LocalDateTime.now());

                snapshotMapper.insert(snapshot);
                log.info("Snapshot created: {} for aggregate: {} version: {}",
                        snapshot.getSnapshotId(), aggregateId, currentVersion);
                return snapshot;
            } finally {
                lock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("Interrupted while acquiring lock for aggregate: " + lockKey);
        }
    }

    public Snapshot getLatestSnapshot(String aggregateId, String aggregateType) {
        return snapshotMapper.findLatestByAggregateId(aggregateId, aggregateType);
    }

    public List<Snapshot> getSnapshots(String aggregateId, String aggregateType) {
        return snapshotMapper.findAllByAggregateId(aggregateId, aggregateType);
    }

    public Map<String, Object> timeTravelQuery(TimetravelQueryRequest request) {
        if (request == null) {
            throw new BusinessException("Request cannot be null");
        }
        if (request.getAggregateId() == null || request.getAggregateId().isBlank()) {
            throw new BusinessException("aggregateId cannot be null or blank");
        }
        if (request.getAggregateType() == null || request.getAggregateType().isBlank()) {
            throw new BusinessException("aggregateType cannot be null or blank");
        }
        if (request.getTimestamp() == null) {
            throw new BusinessException("timestamp cannot be null");
        }

        Snapshot snapshot = snapshotMapper.findByAggregateIdAndTimestampBefore(
                request.getAggregateId(), request.getAggregateType(), request.getTimestamp());

        int fromVersion = snapshot != null ? snapshot.getVersion() : 0;
        List<EventLog> events = eventLogMapper.findByAggregateIdAndTimestampBefore(
                request.getAggregateId(), request.getAggregateType(), request.getTimestamp());

        Map<String, Object> state = snapshot != null ?
                new ConcurrentHashMap<>(snapshot.getState()) : new ConcurrentHashMap<>();
        for (EventLog event : events) {
            if (event.getVersion() > fromVersion) {
                applyEvent(state, event);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("state", Collections.unmodifiableMap(state));
        result.put("timestamp", request.getTimestamp());
        result.put("version", events.isEmpty() ? fromVersion : events.get(events.size() - 1).getVersion());
        result.put("eventCount", events.size());
        return Collections.unmodifiableMap(result);
    }

    public Map<String, Object> rebuildProjection(ProjectionRebuildRequest request) {
        if (request == null) {
            throw new BusinessException("Request cannot be null");
        }
        if (request.getAggregateId() == null || request.getAggregateId().isBlank()) {
            throw new BusinessException("aggregateId cannot be null or blank");
        }
        if (request.getAggregateType() == null || request.getAggregateType().isBlank()) {
            throw new BusinessException("aggregateType cannot be null or blank");
        }

        Snapshot latestSnapshot = snapshotMapper.findLatestByAggregateId(
                request.getAggregateId(), request.getAggregateType());

        int fromVersion = request.getFromVersion() != null ? request.getFromVersion() : 0;
        if (latestSnapshot != null && fromVersion <= latestSnapshot.getVersion()) {
            fromVersion = latestSnapshot.getVersion();
        }

        List<EventLog> events;
        Map<String, Object> state;

        if (latestSnapshot != null && fromVersion == latestSnapshot.getVersion()) {
            state = new ConcurrentHashMap<>(latestSnapshot.getState());
            events = eventLogMapper.findByAggregateIdAndVersionGreaterThan(
                    request.getAggregateId(), request.getAggregateType(), fromVersion);
        } else {
            state = new ConcurrentHashMap<>();
            events = eventLogMapper.findByAggregateIdAndVersionGreaterThan(
                    request.getAggregateId(), request.getAggregateType(), fromVersion);
        }

        if (request.getToVersion() != null) {
            events = events.stream()
                    .filter(e -> e.getVersion() <= request.getToVersion())
                    .toList();
        }

        for (EventLog event : events) {
            applyEvent(state, event);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("state", Collections.unmodifiableMap(state));
        result.put("fromVersion", fromVersion);
        result.put("toVersion", events.isEmpty() ? fromVersion : events.get(events.size() - 1).getVersion());
        result.put("eventCount", events.size());
        return Collections.unmodifiableMap(result);
    }

    private Map<String, Object> rebuildState(List<EventLog> events) {
        Map<String, Object> state = new HashMap<>();
        for (EventLog event : events) {
            applyEvent(state, event);
        }
        return state;
    }

    private void applyEvent(Map<String, Object> state, EventLog event) {
        Map<String, Object> payload = event.getPayload();
        if (payload != null) {
            switch (event.getEventType()) {
                case "CREATED", "UPDATED" -> state.putAll(payload);
                case "DELETED" -> state.clear();
                case "PATCHED" -> applyPatch(state, payload);
                default -> state.put(event.getEventType(), payload);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void applyPatch(Map<String, Object> state, Map<String, Object> patch) {
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map && state.get(key) instanceof Map) {
                Map<String, Object> existing = (Map<String, Object>) state.get(key);
                existing.putAll((Map<String, Object>) value);
            } else {
                state.put(key, value);
            }
        }
    }

    private Map<String, Object> calculateMetrics(List<EventLog> events) {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("totalEvents", events.size());
        metrics.put("firstEventAt", events.isEmpty() ? null : events.get(0).getCreatedAt());
        metrics.put("lastEventAt", events.isEmpty() ? null : events.get(events.size() - 1).getCreatedAt());
        return metrics;
    }
}
