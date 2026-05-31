package com.metricplatform.event;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

@Slf4j
@Data
@Component
public class GatewayEventHistory {

    private static final int MAX_HISTORY_SIZE = 10000;
    private final Deque<GatewayEvent> eventHistory = new ConcurrentLinkedDeque<>();

    public void addEvent(GatewayEvent event) {
        eventHistory.addFirst(event);
        while (eventHistory.size() > MAX_HISTORY_SIZE) {
            eventHistory.removeLast();
        }
    }

    public List<GatewayEvent> getRecentEvents(int limit) {
        List<GatewayEvent> events = new ArrayList<>();
        Iterator<GatewayEvent> iterator = eventHistory.iterator();
        while (iterator.hasNext() && events.size() < limit) {
            events.add(iterator.next());
        }
        return events;
    }

    public List<GatewayEvent> getEventsByType(GatewayEvent.EventType type, int limit) {
        List<GatewayEvent> events = new ArrayList<>();
        for (GatewayEvent event : eventHistory) {
            if (event.getEventType() == type && events.size() < limit) {
                events.add(event);
            }
        }
        return events;
    }

    public List<GatewayEvent> getEventsByLevel(GatewayEvent.EventLevel level, int limit) {
        List<GatewayEvent> events = new ArrayList<>();
        for (GatewayEvent event : eventHistory) {
            if (event.getEventLevel() == level && events.size() < limit) {
                events.add(event);
            }
        }
        return events;
    }

    public List<GatewayEvent> getEventsByTimeRange(LocalDateTime startTime, LocalDateTime endTime, int limit) {
        List<GatewayEvent> events = new ArrayList<>();
        for (GatewayEvent event : eventHistory) {
            if (event.getTimestamp() != null &&
                    !event.getTimestamp().isBefore(startTime) &&
                    !event.getTimestamp().isAfter(endTime) &&
                    events.size() < limit) {
                events.add(event);
            }
        }
        return events;
    }

    public Map<String, Long> getEventStats(LocalDateTime startTime, LocalDateTime endTime) {
        Map<String, Long> stats = new HashMap<>();
        stats.put("total", 0L);

        for (GatewayEvent.EventType type : GatewayEvent.EventType.values()) {
            stats.put(type.name(), 0L);
        }
        for (GatewayEvent.EventLevel level : GatewayEvent.EventLevel.values()) {
            stats.put(level.name(), 0L);
        }

        for (GatewayEvent event : eventHistory) {
            if (event.getTimestamp() == null ||
                    event.getTimestamp().isBefore(startTime) ||
                    event.getTimestamp().isAfter(endTime)) {
                continue;
            }

            stats.merge("total", 1L, Long::sum);
            stats.merge(event.getEventType().name(), 1L, Long::sum);
            stats.merge(event.getEventLevel().name(), 1L, Long::sum);
        }

        return stats;
    }

    public void clearHistory() {
        eventHistory.clear();
        log.info("网关事件历史已清空");
    }

    public int getHistorySize() {
        return eventHistory.size();
    }
}
