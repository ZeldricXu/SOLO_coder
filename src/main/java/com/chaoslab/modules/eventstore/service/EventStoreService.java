package com.chaoslab.modules.eventstore.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chaoslab.common.JsonUtils;
import com.chaoslab.entity.EventLog;
import com.chaoslab.entity.EventProjection;
import com.chaoslab.entity.EventSnapshot;
import com.chaoslab.event.DomainEvent;
import com.chaoslab.event.EventPublisher;
import com.chaoslab.exception.BusinessException;
import com.chaoslab.mapper.EventLogMapper;
import com.chaoslab.mapper.EventProjectionMapper;
import com.chaoslab.mapper.EventSnapshotMapper;
import com.chaoslab.modules.eventstore.dto.EventAppendRequest;
import com.chaoslab.modules.eventstore.dto.ProjectionCreateRequest;
import com.chaoslab.modules.eventstore.dto.TimeTravelQueryRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventStoreService {

    private final EventLogMapper eventLogMapper;
    private final EventSnapshotMapper snapshotMapper;
    private final EventProjectionMapper projectionMapper;
    private final EventPublisher eventPublisher;

    @Value("${chaoslab.eventstore.snapshot-interval:100}")
    private int snapshotInterval;

    @Value("${chaoslab.eventstore.max-events-per-query:1000}")
    private int maxEventsPerQuery;

    private final Map<String, Long> aggregateSequenceCache = new ConcurrentHashMap<>();

    @Transactional
    public Mono<EventLog> appendEvent(EventAppendRequest request) {
        return eventPublisher.publish(
                        request.getEventType(),
                        request.getAggregateId(),
                        request.getAggregateType(),
                        request.getPayload()
                )
                .map(event -> {
                    EventLog eventLog = new EventLog();
                    eventLog.setEventId(event.getEventId());
                    eventLog.setEventType(event.getEventType());
                    eventLog.setEventVersion(event.getEventVersion());
                    eventLog.setAggregateId(event.getAggregateId());
                    eventLog.setAggregateType(event.getAggregateType());
                    eventLog.setPayload(request.getPayload());
                    eventLog.setMetadata(request.getMetadata() != null ? request.getMetadata() : event.getMetadata());
                    eventLog.setTimestamp(event.getTimestamp());
                    eventLog.setCreatedAt(LocalDateTime.now());

                    Long sequenceNumber = eventLogMapper.getNextSequence(request.getAggregateId());
                    eventLog.setSequenceNumber(sequenceNumber);

                    eventLogMapper.insert(eventLog);
                    aggregateSequenceCache.put(request.getAggregateId(), sequenceNumber);

                    checkAndCreateSnapshot(request.getAggregateId(), request.getAggregateType(), sequenceNumber);

                    log.info("Appended event: {} type: {} sequence: {}",
                            eventLog.getEventId(), eventLog.getEventType(), sequenceNumber);
                    return eventLog;
                });
    }

    public Mono<List<EventLog>> getEvents(String aggregateId, Long fromSequence, Integer limit) {
        return Mono.fromCallable(() -> {
            int actualLimit = limit != null ? Math.min(limit, maxEventsPerQuery) : maxEventsPerQuery;
            Long actualFrom = fromSequence != null ? fromSequence : 0L;
            return eventLogMapper.findByAggregateIdAndSequence(aggregateId, actualFrom, actualLimit);
        });
    }

    public Flux<EventLog> streamEvents(String aggregateId, Long fromSequence) {
        return Flux.defer(() -> {
            LambdaQueryWrapper<EventLog> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(EventLog::getAggregateId, aggregateId)
                    .gt(fromSequence != null, EventLog::getSequenceNumber, fromSequence)
                    .orderByAsc(EventLog::getSequenceNumber)
                    .last("LIMIT " + maxEventsPerQuery);
            return Flux.fromIterable(eventLogMapper.selectList(wrapper));
        });
    }

    @Transactional
    public Mono<EventSnapshot> createSnapshot(String aggregateId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<EventLog> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(EventLog::getAggregateId, aggregateId)
                    .orderByAsc(EventLog::getSequenceNumber);
            List<EventLog> events = eventLogMapper.selectList(wrapper);

            if (events.isEmpty()) {
                throw BusinessException.notFound("没有找到该聚合根的事件: " + aggregateId);
            }

            Map<String, Object> state = rebuildState(events);
            EventLog lastEvent = events.get(events.size() - 1);

            EventSnapshot snapshot = new EventSnapshot();
            snapshot.setSnapshotId("snap-" + UUID.randomUUID().toString().substring(0, 8));
            snapshot.setAggregateId(aggregateId);
            snapshot.setAggregateType(lastEvent.getAggregateType());
            snapshot.setState(state);
            snapshot.setSequenceNumber(lastEvent.getSequenceNumber());
            snapshot.setVersion(1);
            snapshot.setCreatedAt(LocalDateTime.now());

            snapshotMapper.insert(snapshot);
            log.info("Created snapshot: {} for aggregate: {} at sequence: {}",
                    snapshot.getSnapshotId(), aggregateId, lastEvent.getSequenceNumber());
            return snapshot;
        });
    }

    public Mono<EventSnapshot> getLatestSnapshot(String aggregateId) {
        return Mono.fromCallable(() -> {
            EventSnapshot snapshot = snapshotMapper.findLatestByAggregateId(aggregateId);
            if (snapshot == null) {
                throw BusinessException.notFound("没有找到该聚合根的快照: " + aggregateId);
            }
            return snapshot;
        });
    }

    public Mono<Map<String, Object>> timeTravelQuery(TimeTravelQueryRequest request) {
        return Mono.fromCallable(() -> {
            List<EventLog> events;

            if (request.getTimestamp() != null) {
                events = eventLogMapper.findByAggregateIdAndTimestamp(
                        request.getAggregateId(), request.getTimestamp());
            } else if (request.getSequenceNumber() != null) {
                events = eventLogMapper.findByAggregateIdAndSequence(
                        request.getAggregateId(), 0L, request.getSequenceNumber().intValue());
            } else {
                throw BusinessException.validationError("必须指定时间点或序列号");
            }

            if (events.isEmpty()) {
                throw BusinessException.notFound("指定时间点没有找到事件");
            }

            Map<String, Object> state = rebuildState(events);

            Map<String, Object> result = new HashMap<>();
            result.put("aggregateId", request.getAggregateId());
            result.put("aggregateType", events.get(events.size() - 1).getAggregateType());
            result.put("state", state);
            result.put("asOfSequence", events.get(events.size() - 1).getSequenceNumber());
            result.put("asOfTimestamp", events.get(events.size() - 1).getTimestamp());
            result.put("eventCount", events.size());

            return result;
        });
    }

    @Transactional
    public Mono<EventProjection> createProjection(ProjectionCreateRequest request) {
        return Mono.fromCallable(() -> {
            EventProjection projection = new EventProjection();
            projection.setProjectionId("proj-" + UUID.randomUUID().toString().substring(0, 8));
            projection.setName(request.getName());
            projection.setAggregateType(request.getAggregateType());
            projection.setHandlerConfig(request.getHandlerConfig());
            projection.setLastSequence(0L);
            projection.setStatus("running");
            projection.setRebuildInProgress(false);

            projectionMapper.insert(projection);
            log.info("Created projection: {} for type: {}", projection.getProjectionId(), request.getAggregateType());
            return projection;
        });
    }

    @Transactional
    public Mono<EventProjection> rebuildProjection(String projectionId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<EventProjection> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(EventProjection::getProjectionId, projectionId);
            EventProjection projection = projectionMapper.selectOne(wrapper);
            if (projection == null) {
                throw BusinessException.notFound("投影不存在: " + projectionId);
            }

            projection.setRebuildInProgress(true);
            projection.setStatus("rebuilding");
            projectionMapper.updateById(projection);

            startProjectionRebuildAsync(projection);

            return projection;
        });
    }

    @Async
    @Transactional
    public void startProjectionRebuildAsync(EventProjection projection) {
        try {
            log.info("Starting projection rebuild: {}", projection.getProjectionId());

            long lastProcessed = 0L;
            while (true) {
                LambdaQueryWrapper<EventLog> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(EventLog::getAggregateType, projection.getAggregateType())
                        .gt(EventLog::getSequenceNumber, lastProcessed)
                        .orderByAsc(EventLog::getSequenceNumber)
                        .last("LIMIT 100");
                List<EventLog> events = eventLogMapper.selectList(wrapper);

                if (events.isEmpty()) {
                    break;
                }

                for (EventLog event : events) {
                    applyEventToProjection(projection, event);
                    lastProcessed = event.getSequenceNumber();
                }

                projection.setLastSequence(lastProcessed);
                projectionMapper.updateById(projection);
            }

            projection.setRebuildInProgress(false);
            projection.setStatus("running");
            projectionMapper.updateById(projection);
            log.info("Completed projection rebuild: {} up to sequence: {}", projection.getProjectionId(), lastProcessed);
        } catch (Exception e) {
            projection.setRebuildInProgress(false);
            projection.setStatus("failed");
            projectionMapper.updateById(projection);
            log.error("Projection rebuild failed: {}", projection.getProjectionId(), e);
        }
    }

    @EventListener
    @Transactional
    public <T> void handleDomainEvent(DomainEvent<T> event) {
        processProjections(event);
    }

    @Transactional
    public void processProjections(DomainEvent<?> event) {
        LambdaQueryWrapper<EventProjection> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(EventProjection::getAggregateType, event.getAggregateType())
                .eq(EventProjection::getStatus, "running")
                .eq(EventProjection::getRebuildInProgress, false);
        List<EventProjection> projections = projectionMapper.selectList(wrapper);

        for (EventProjection projection : projections) {
            try {
                EventLog eventLog = new EventLog();
                eventLog.setEventId(event.getEventId());
                eventLog.setEventType(event.getEventType());
                eventLog.setAggregateId(event.getAggregateId());
                eventLog.setAggregateType(event.getAggregateType());
                eventLog.setPayload(JsonUtils.fromJson(JsonUtils.toJson(event.getPayload()),
                        new TypeReference<Map<String, Object>>() {}));
                eventLog.setTimestamp(event.getTimestamp());
                eventLog.setSequenceNumber(aggregateSequenceCache.getOrDefault(event.getAggregateId(), 0L));

                applyEventToProjection(projection, eventLog);
                projection.setLastSequence(eventLog.getSequenceNumber());
                projectionMapper.updateById(projection);
            } catch (Exception e) {
                log.error("Failed to apply event to projection: {}", projection.getProjectionId(), e);
            }
        }
    }

    public Mono<List<EventProjection>> listProjections() {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<EventProjection> wrapper = new LambdaQueryWrapper<>();
            wrapper.orderByDesc(EventProjection::getCreatedAt);
            return projectionMapper.selectList(wrapper);
        });
    }

    public Mono<Map<String, Object>> getStats() {
        return Mono.fromCallable(() -> {
            Map<String, Object> stats = new HashMap<>();
            LambdaQueryWrapper<EventLog> logWrapper = new LambdaQueryWrapper<>();
            stats.put("totalEvents", eventLogMapper.selectCount(logWrapper));
            stats.put("globalMaxSequence", eventLogMapper.getGlobalMaxSequence());

            LambdaQueryWrapper<EventSnapshot> snapshotWrapper = new LambdaQueryWrapper<>();
            stats.put("totalSnapshots", snapshotMapper.selectCount(snapshotWrapper));

            LambdaQueryWrapper<EventProjection> projectionWrapper = new LambdaQueryWrapper<>();
            stats.put("totalProjections", projectionMapper.selectCount(projectionWrapper));

            return stats;
        });
    }

    private void checkAndCreateSnapshot(String aggregateId, String aggregateType, Long sequenceNumber) {
        if (sequenceNumber % snapshotInterval == 0) {
            try {
                LambdaQueryWrapper<EventLog> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(EventLog::getAggregateId, aggregateId)
                        .orderByAsc(EventLog::getSequenceNumber);
                List<EventLog> events = eventLogMapper.selectList(wrapper);
                Map<String, Object> state = rebuildState(events);

                EventSnapshot snapshot = new EventSnapshot();
                snapshot.setSnapshotId("snap-" + UUID.randomUUID().toString().substring(0, 8));
                snapshot.setAggregateId(aggregateId);
                snapshot.setAggregateType(aggregateType);
                snapshot.setState(state);
                snapshot.setSequenceNumber(sequenceNumber);
                snapshot.setVersion(1);
                snapshot.setCreatedAt(LocalDateTime.now());

                snapshotMapper.insert(snapshot);
                log.info("Auto-created snapshot for aggregate: {} at sequence: {}", aggregateId, sequenceNumber);
            } catch (Exception e) {
                log.error("Failed to create auto-snapshot for aggregate: {}", aggregateId, e);
            }
        }
    }

    private Map<String, Object> rebuildState(List<EventLog> events) {
        Map<String, Object> state = new HashMap<>();
        for (EventLog event : events) {
            applyEventToState(state, event);
        }
        return state;
    }

    private void applyEventToState(Map<String, Object> state, EventLog event) {
        Map<String, Object> payload = event.getPayload();
        if (payload == null) {
            return;
        }

        switch (event.getEventType()) {
            case "entity.created", "created" -> state.putAll(payload);
            case "entity.updated", "updated" -> {
                for (Map.Entry<String, Object> entry : payload.entrySet()) {
                    state.put(entry.getKey(), entry.getValue());
                }
            }
            case "entity.deleted", "deleted" -> state.put("deleted", true);
            default -> state.put(event.getEventType(), payload);
        }
    }

    private void applyEventToProjection(EventProjection projection, EventLog event) {
        Map<String, Object> config = projection.getHandlerConfig();
        if (config == null || !config.containsKey("handlers")) {
            return;
        }

        Map<String, Object> handlers = (Map<String, Object>) config.get("handlers");
        if (handlers.containsKey(event.getEventType())) {
            log.debug("Applied event {} to projection {}", event.getEventId(), projection.getProjectionId());
        }
    }
}
