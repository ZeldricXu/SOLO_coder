package com.enterprise.risk.storage.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventContextService {

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    private static final String EVENT_CONTEXT_PREFIX = "risk:context:";
    private static final String EVENT_LIST_PREFIX = "risk:events:";
    private static final long DEFAULT_TTL_HOURS = 24;

    /**
     * 存储事件到实体上下文
     *
     * @param entityType 实体类型
     * @param entityId   实体ID
     * @param eventId    事件ID
     * @param eventType  事件类型
     * @param eventData  事件数据
     * @param maxEvents  最大保留事件数
     */
    public void storeEvent(String entityType, String entityId,
                           String eventId, String eventType,
                           Map<String, Object> eventData,
                           int maxEvents) {
        String entityKey = buildEntityKey(entityType, entityId);
        String listKey = buildListKey(entityType, entityId);

        RScoredSortedSet<String> eventList = redissonClient.getScoredSortedSet(listKey);
        long now = Instant.now().toEpochMilli();

        try {
            Map<String, Object> storageData = new HashMap<>(eventData != null ? eventData : new HashMap<>());
            storageData.put("_eventId", eventId);
            storageData.put("_eventType", eventType);
            storageData.put("_timestamp", now);

            String json = objectMapper.writeValueAsString(storageData);

            RMap<String, String> context = redissonClient.getMap(entityKey);
            context.put(eventId, json);
            eventList.add(now, eventId);

            while (eventList.size() > maxEvents) {
                String removedId = eventList.pollFirst();
                if (removedId != null) {
                    context.remove(removedId);
                }
            }

            long ttl = DEFAULT_TTL_HOURS;
            context.expire(ttl, TimeUnit.HOURS);
            eventList.expire(ttl, TimeUnit.HOURS);
        } catch (Exception e) {
            log.error("存储事件上下文失败: entityKey={}, eventId={}", entityKey, eventId, e);
        }
    }

    /**
     * 获取实体最近的N条事件
     *
     * @param entityType 实体类型
     * @param entityId   实体ID
     * @param limit      返回数量
     * @return 事件列表（按时间倒序）
     */
    public List<Map<String, Object>> getRecentEvents(String entityType, String entityId, int limit) {
        String entityKey = buildEntityKey(entityType, entityId);
        String listKey = buildListKey(entityType, entityId);

        RMap<String, String> context = redissonClient.getMap(entityKey);
        RScoredSortedSet<String> eventList = redissonClient.getScoredSortedSet(listKey);

        int size = eventList.size();
        int start = Math.max(0, size - limit);
        Collection<String> eventIds = eventList.valueRange(start, size - 1);

        List<Map<String, Object>> result = new ArrayList<>();
        List<String> idList = new ArrayList<>(eventIds);
        Collections.reverse(idList);

        for (String eventId : idList) {
            String json = context.get(eventId);
            if (json != null) {
                try {
                    Map<String, Object> event = objectMapper.readValue(json, new TypeReference<>() {});
                    result.add(event);
                } catch (Exception e) {
                    log.warn("反序列化事件失败: {}", json, e);
                }
            }
        }
        return result;
    }

    /**
     * 获取指定事件类型的最近事件
     *
     * @param entityType 实体类型
     * @param entityId   实体ID
     * @param eventType  事件类型
     * @param limit      返回数量
     * @return 事件列表
     */
    public List<Map<String, Object>> getRecentEventsByType(String entityType, String entityId,
                                                           String eventType, int limit) {
        List<Map<String, Object>> all = getRecentEvents(entityType, entityId, limit * 5);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> event : all) {
            if (eventType.equals(event.get("_eventType"))) {
                result.add(event);
                if (result.size() >= limit) break;
            }
        }
        return result;
    }

    /**
     * 获取实体某个字段的历史值
     *
     * @param entityType 实体类型
     * @param entityId   实体ID
     * @param fieldName  字段名
     * @param limit      返回数量
     * @return 字段值列表（含时间戳）
     */
    public List<Map<String, Object>> getFieldHistory(String entityType, String entityId,
                                                     String fieldName, int limit) {
        List<Map<String, Object>> events = getRecentEvents(entityType, entityId, limit);
        List<Map<String, Object>> history = new ArrayList<>();
        for (Map<String, Object> event : events) {
            if (event.containsKey(fieldName)) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("value", event.get(fieldName));
                entry.put("timestamp", event.get("_timestamp"));
                entry.put("eventId", event.get("_eventId"));
                history.add(entry);
            }
        }
        return history;
    }

    /**
     * 获取单个事件
     *
     * @param entityType 实体类型
     * @param entityId   实体ID
     * @param eventId    事件ID
     * @return 事件数据（不存在返回空）
     */
    public Optional<Map<String, Object>> getEvent(String entityType, String entityId, String eventId) {
        String entityKey = buildEntityKey(entityType, entityId);
        RMap<String, String> context = redissonClient.getMap(entityKey);
        String json = context.get(eventId);
        if (json == null) {
            return Optional.empty();
        }
        try {
            Map<String, Object> event = objectMapper.readValue(json, new TypeReference<>() {});
            return Optional.of(event);
        } catch (Exception e) {
            log.warn("反序列化事件失败: {}", json, e);
            return Optional.empty();
        }
    }

    /**
     * 清除实体所有上下文
     *
     * @param entityType 实体类型
     * @param entityId   实体ID
     */
    public void clearContext(String entityType, String entityId) {
        redissonClient.getMap(buildEntityKey(entityType, entityId)).delete();
        redissonClient.getScoredSortedSet(buildListKey(entityType, entityId)).delete();
    }

    /**
     * 获取实体存储的事件总数
     *
     * @param entityType 实体类型
     * @param entityId   实体ID
     * @return 事件数
     */
    public int getEventCount(String entityType, String entityId) {
        String listKey = buildListKey(entityType, entityId);
        return redissonClient.getScoredSortedSet(listKey).size();
    }

    /**
     * 获取实体在指定时间范围内的事件数
     *
     * @param entityType 实体类型
     * @param entityId   实体ID
     * @param startTime  开始时间
     * @param endTime    结束时间
     * @return 事件数
     */
    public int getEventCountInRange(String entityType, String entityId, long startTime, long endTime) {
        String listKey = buildListKey(entityType, entityId);
        RScoredSortedSet<String> eventList = redissonClient.getScoredSortedSet(listKey);
        return eventList.count(startTime, true, endTime, true);
    }

    private String buildEntityKey(String entityType, String entityId) {
        return EVENT_CONTEXT_PREFIX + entityType + ":" + entityId;
    }

    private String buildListKey(String entityType, String entityId) {
        return EVENT_LIST_PREFIX + entityType + ":" + entityId;
    }
}
