package com.solo.config.module.event;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.solo.config.common.IdGenerator;
import com.solo.config.entity.Event;
import com.solo.config.entity.Snapshot;
import com.solo.config.mapper.EventMapper;
import com.solo.config.mapper.SnapshotMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventStoreService {

    private final EventMapper eventMapper;
    private final SnapshotMapper snapshotMapper;

    private static final int SNAPSHOT_INTERVAL = 100;

    public Mono<Event> appendEvent(String aggregateType, String aggregateId, String eventType,
                                   Map<String, Object> payload, Map<String, Object> metadata) {
        return Mono.fromCallable(() -> {
            int currentVersion = getCurrentVersion(aggregateType, aggregateId);
            int nextVersion = currentVersion + 1;

            Event event = new Event();
            event.setEventId(IdGenerator.generateEventId());
            event.setAggregateType(aggregateType);
            event.setAggregateId(aggregateId);
            event.setEventType(eventType);
            event.setVersion(nextVersion);
            event.setPayload(payload);
            event.setMetadata(metadata);
            event.setTimestamp(LocalDateTime.now());

            eventMapper.insert(event);
            log.info("Event appended: {}, aggregate: {}, version: {}", eventType, aggregateId, nextVersion);

            if (nextVersion % SNAPSHOT_INTERVAL == 0) {
                createSnapshot(aggregateType, aggregateId, nextVersion);
            }

            return event;
        });
    }

    private int getCurrentVersion(String aggregateType, String aggregateId) {
        Event lastEvent = eventMapper.selectOne(
                new QueryWrapper<Event>()
                        .eq("aggregate_type", aggregateType)
                        .eq("aggregate_id", aggregateId)
                        .orderByDesc("version")
                        .last("LIMIT 1")
        );
        return lastEvent != null ? lastEvent.getVersion() : 0;
    }

    public Flux<Event> loadEvents(String aggregateType, String aggregateId, Integer fromVersion) {
        return Flux.fromIterable(
                eventMapper.selectList(
                        new QueryWrapper<Event>()
                                .eq("aggregate_type", aggregateType)
                                .eq("aggregate_id", aggregateId)
                                .ge(fromVersion != null, "version", fromVersion)
                                .orderByAsc("version")
                )
        );
    }

    public Mono<Map<String, Object>> replayAggregate(String aggregateType, String aggregateId) {
        return Mono.fromCallable(() -> {
            Snapshot latestSnapshot = snapshotMapper.selectOne(
                    new QueryWrapper<Snapshot>()
                            .eq("aggregate_type", aggregateType)
                            .eq("aggregate_id", aggregateId)
                            .orderByDesc("version")
                            .last("LIMIT 1")
            );

            Map<String, Object> state = new HashMap<>();
            int fromVersion = 1;

            if (latestSnapshot != null) {
                state.putAll(latestSnapshot.getState());
                fromVersion = latestSnapshot.getVersion() + 1;
            }

            List<Event> events = eventMapper.selectList(
                    new QueryWrapper<Event>()
                            .eq("aggregate_type", aggregateType)
                            .eq("aggregate_id", aggregateId)
                            .ge("version", fromVersion)
                            .orderByAsc("version")
            );

            for (Event event : events) {
                applyEvent(state, event);
            }

            return state;
        });
    }

    public Mono<Map<String, Object>> timeTravelQuery(String aggregateType, String aggregateId, LocalDateTime timestamp) {
        return Mono.fromCallable(() -> {
            List<Event> events = eventMapper.selectList(
                    new QueryWrapper<Event>()
                            .eq("aggregate_type", aggregateType)
                            .eq("aggregate_id", aggregateId)
                            .le("timestamp", timestamp)
                            .orderByAsc("version")
            );

            Map<String, Object> state = new HashMap<>();
            for (Event event : events) {
                applyEvent(state, event);
            }

            return state;
        });
    }

    private void applyEvent(Map<String, Object> state, Event event) {
        if (event.getPayload() != null) {
            state.putAll(event.getPayload());
        }
        state.put("lastEventType", event.getEventType());
        state.put("lastEventVersion", event.getVersion());
        state.put("lastEventTime", event.getTimestamp());
    }

    private void createSnapshot(String aggregateType, String aggregateId, int version) {
        try {
            Map<String, Object> state = replayAggregate(aggregateType, aggregateId).block();

            Snapshot snapshot = new Snapshot();
            snapshot.setSnapshotId(IdGenerator.generateSnapshotId());
            snapshot.setAggregateType(aggregateType);
            snapshot.setAggregateId(aggregateId);
            snapshot.setVersion(version);
            snapshot.setState(state);

            snapshotMapper.insert(snapshot);
            log.info("Snapshot created for aggregate: {}, version: {}", aggregateId, version);
        } catch (Exception e) {
            log.error("Failed to create snapshot for aggregate: {}", aggregateId, e);
        }
    }

    public Flux<Event> listEvents(String aggregateType, String aggregateId, int page, int size) {
        return Flux.fromIterable(
                eventMapper.selectList(
                        new QueryWrapper<Event>()
                                .eq(aggregateType != null, "aggregate_type", aggregateType)
                                .eq(aggregateId != null, "aggregate_id", aggregateId)
                                .orderByDesc("timestamp")
                                .last("LIMIT " + size + " OFFSET " + (page - 1) * size)
                )
        );
    }

    public Flux<Snapshot> listSnapshots(String aggregateType, String aggregateId) {
        return Flux.fromIterable(
                snapshotMapper.selectList(
                        new QueryWrapper<Snapshot>()
                                .eq(aggregateType != null, "aggregate_type", aggregateType)
                                .eq(aggregateId != null, "aggregate_id", aggregateId)
                                .orderByDesc("version")
                )
        );
    }

    public Mono<Long> getEventCount(String aggregateType, String aggregateId) {
        return Mono.fromCallable(() ->
                eventMapper.selectCount(
                        new QueryWrapper<Event>()
                                .eq(aggregateType != null, "aggregate_type", aggregateType)
                                .eq(aggregateId != null, "aggregate_id", aggregateId)
                )
        );
    }

    public Flux<Event> getEventsByType(String eventType) {
        return Flux.fromIterable(
                eventMapper.selectList(
                        new QueryWrapper<Event>()
                                .eq("event_type", eventType)
                                .orderByDesc("timestamp")
                                .last("LIMIT 1000")
                )
        );
    }
}
