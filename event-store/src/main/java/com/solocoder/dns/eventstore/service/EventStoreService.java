package com.solocoder.dns.eventstore.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.solocoder.dns.common.entity.DomainEvent;
import com.solocoder.dns.common.model.PageResult;
import com.solocoder.dns.common.util.IdGenerator;
import com.solocoder.dns.common.util.JsonUtils;
import com.solocoder.dns.eventstore.model.EventQuery;
import com.solocoder.dns.eventstore.model.Snapshot;
import com.solocoder.dns.persistence.entity.DomainEventPO;
import com.solocoder.dns.persistence.mapper.DomainEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventStoreService {
    private final DomainEventMapper eventMapper;
    private final Map<String, Snapshot> snapshotStore = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> sequenceCounters = new ConcurrentHashMap<>();

    @EventListener
    public void handleDomainEvent(DomainEvent event) {
        appendEvent(event);
    }

    public DomainEvent appendEvent(DomainEvent event) {
        if (event.getEventId() == null) {
            event.setEventId(IdGenerator.generateEventId());
        }
        if (event.getSequence() == null) {
            AtomicLong counter = sequenceCounters.computeIfAbsent(event.getAggregateId(), k -> new AtomicLong(0));
            event.setSequence(counter.incrementAndGet());
        }
        if (event.getOccurredAt() == null) {
            event.setOccurredAt(LocalDateTime.now());
        }
        eventMapper.insert(toPO(event));
        log.debug("Event stored: {} - {} for {}", event.getEventId(), event.getEventType(), event.getAggregateId());
        return event;
    }

    public List<DomainEvent> loadEvents(String aggregateId) {
        return loadEvents(aggregateId, 0L, Long.MAX_VALUE);
    }

    public List<DomainEvent> loadEvents(String aggregateId, Long fromSequence, Long toSequence) {
        LambdaQueryWrapper<DomainEventPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DomainEventPO::getAggregateId, aggregateId);
        wrapper.ge(DomainEventPO::getSequence, fromSequence);
        wrapper.le(DomainEventPO::getSequence, toSequence);
        wrapper.orderByAsc(DomainEventPO::getSequence);
        return eventMapper.selectList(wrapper).stream().map(this::toDomain).collect(Collectors.toList());
    }

    public List<DomainEvent> loadEventsByType(String aggregateId, String eventType) {
        LambdaQueryWrapper<DomainEventPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DomainEventPO::getAggregateId, aggregateId);
        wrapper.eq(DomainEventPO::getEventType, eventType);
        wrapper.orderByAsc(DomainEventPO::getSequence);
        return eventMapper.selectList(wrapper).stream().map(this::toDomain).collect(Collectors.toList());
    }

    public Snapshot createSnapshot(String aggregateId, Object state) {
        Snapshot snapshot = new Snapshot();
        snapshot.setSnapshotId(IdGenerator.generateId("snap"));
        snapshot.setAggregateId(aggregateId);
        snapshot.setState(JsonUtils.toJson(state));
        snapshot.setCreatedAt(LocalDateTime.now());

        AtomicLong counter = sequenceCounters.get(aggregateId);
        snapshot.setVersion(counter != null ? counter.intValue() : 0);

        snapshotStore.put(snapshot.getSnapshotId(), snapshot);
        log.debug("Snapshot created: {} for aggregate {}", snapshot.getSnapshotId(), aggregateId);
        return snapshot;
    }

    public Snapshot getLatestSnapshot(String aggregateId) {
        return snapshotStore.values().stream()
                .filter(s -> aggregateId.equals(s.getAggregateId()))
                .max((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .orElse(null);
    }

    public Object reconstructState(String aggregateId) {
        Snapshot snapshot = getLatestSnapshot(aggregateId);
        List<DomainEvent> events;
        Map<String, Object> state;

        if (snapshot != null) {
            state = JsonUtils.fromJson(snapshot.getState(), Map.class);
            events = loadEvents(aggregateId, snapshot.getVersion().longValue() + 1, Long.MAX_VALUE);
        } else {
            state = new ConcurrentHashMap<>();
            events = loadEvents(aggregateId);
        }

        for (DomainEvent event : events) {
            applyEvent(state, event);
        }

        return state;
    }

    private void applyEvent(Map<String, Object> state, DomainEvent event) {
        Map<String, Object> payload = event.getPayload();
        if (payload != null) {
            state.putAll(payload);
        }
    }

    public List<DomainEvent> timeTravelQuery(String aggregateId, LocalDateTime pointInTime) {
        LambdaQueryWrapper<DomainEventPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DomainEventPO::getAggregateId, aggregateId);
        wrapper.le(DomainEventPO::getOccurredAt, pointInTime);
        wrapper.orderByAsc(DomainEventPO::getSequence);
        return eventMapper.selectList(wrapper).stream().map(this::toDomain).collect(Collectors.toList());
    }

    public PageResult<DomainEvent> queryEvents(EventQuery query, int page, int size) {
        LambdaQueryWrapper<DomainEventPO> wrapper = new LambdaQueryWrapper<>();
        if (query.getAggregateId() != null) {
            wrapper.eq(DomainEventPO::getAggregateId, query.getAggregateId());
        }
        if (query.getEventType() != null) {
            wrapper.eq(DomainEventPO::getEventType, query.getEventType());
        }
        wrapper.orderByDesc(DomainEventPO::getOccurredAt);

        Page<DomainEventPO> poPage = eventMapper.selectPage(new Page<>(page, size), wrapper);
        List<DomainEvent> items = poPage.getRecords().stream().map(this::toDomain).collect(Collectors.toList());
        return new PageResult<>(items, poPage.getTotal(), page, size);
    }

    private DomainEventPO toPO(DomainEvent event) {
        DomainEventPO po = new DomainEventPO();
        po.setEventId(event.getEventId());
        po.setAggregateId(event.getAggregateId());
        po.setEventType(event.getEventType());
        po.setPayload(JsonUtils.toJson(event.getPayload()));
        po.setSequence(event.getSequence());
        po.setOccurredAt(event.getOccurredAt());
        po.setMetadata(event.getMetadata());
        return po;
    }

    @SuppressWarnings("unchecked")
    private DomainEvent toDomain(DomainEventPO po) {
        DomainEvent event = new DomainEvent();
        event.setEventId(po.getEventId());
        event.setAggregateId(po.getAggregateId());
        event.setEventType(po.getEventType());
        event.setPayload(po.getPayload() != null ? JsonUtils.fromJson(po.getPayload(), Map.class) : null);
        event.setSequence(po.getSequence());
        event.setOccurredAt(po.getOccurredAt());
        event.setMetadata(po.getMetadata());
        return event;
    }
}
