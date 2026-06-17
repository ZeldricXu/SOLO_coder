package com.enterprise.risk.gateway.deserializer;

import com.enterprise.risk.common.event.RiskEvent;
import com.enterprise.risk.common.exception.EventValidationException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JSON事件转换器
 * 负责JSON字符串与RiskEvent对象之间的相互转换
 * 支持：
 * 1. 单条JSON -> RiskEvent
 * 2. JSON数组 -> List<RiskEvent>
 * 3. Map -> RiskEvent
 * 4. RiskEvent -> JSON字符串
 */
@Slf4j
@Component
public class JsonEventConverter {

    private final ObjectMapper objectMapper;

    public JsonEventConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * JSON字符串转RiskEvent
     */
    public RiskEvent fromJson(String json) {
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});
            return fromMap(map);
        } catch (JsonProcessingException e) {
            throw new EventValidationException("JSON解析失败: " + e.getOriginalMessage(), json);
        }
    }

    /**
     * JSON数组字符串转RiskEvent列表
     */
    public List<RiskEvent> fromJsonBatch(String json) {
        try {
            List<Map<String, Object>> list = objectMapper.readValue(json, new TypeReference<>() {});
            List<RiskEvent> events = new ArrayList<>(list.size());

            for (Map<String, Object> map : list) {
                events.add(fromMap(map));
            }

            return events;
        } catch (JsonProcessingException e) {
            throw new EventValidationException("批量JSON解析失败: " + e.getOriginalMessage(), json);
        }
    }

    /**
     * Map转RiskEvent对象
     * 处理字段映射、类型转换、默认值填充
     */
    @SuppressWarnings("unchecked")
    public RiskEvent fromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            throw new EventValidationException("事件数据不能为空");
        }

        RiskEvent.RiskEventBuilder builder = RiskEvent.builder();

        String eventId = getString(map, "event_id", "eventId");
        if (eventId == null || eventId.isEmpty()) {
            eventId = UUID.randomUUID().toString();
        }
        builder.eventId(eventId);

        String eventType = getString(map, "event_type", "eventType");
        if (eventType != null) {
            builder.eventType(eventType.trim());
        }

        String businessLine = getString(map, "business_line", "businessLine");
        if (businessLine != null) {
            builder.businessLine(businessLine.trim());
        }

        Long timestamp = getLong(map, "timestamp");
        if (timestamp == null) {
            timestamp = Instant.now().toEpochMilli();
        }
        builder.timestamp(timestamp);

        String entityId = getString(map, "entity_id", "entityId");
        if (entityId != null) {
            builder.entityId(entityId.trim());
        }

        String entityType = getString(map, "entity_type", "entityType");
        if (entityType != null) {
            builder.entityType(entityType.trim());
        }

        String source = getString(map, "source");
        if (source != null) {
            builder.source(source.trim());
        }

        String sessionId = getString(map, "session_id", "sessionId");
        if (sessionId != null) {
            builder.sessionId(sessionId.trim());
        }

        String ip = getString(map, "ip");
        if (ip != null) {
            builder.ip(ip.trim());
        }

        String userId = getString(map, "user_id", "userId");
        if (userId != null) {
            builder.userId(userId.trim());
        }

        Map<String, Object> attributes = new HashMap<>();
        Object attrsObj = map.get("attributes");
        if (attrsObj instanceof Map) {
            attributes.putAll((Map<String, Object>) attrsObj);
        }

        List<String> knownFields = List.of(
                "event_id", "eventId", "event_type", "eventType",
                "business_line", "businessLine", "timestamp",
                "entity_id", "entityId", "entity_type", "entityType",
                "source", "session_id", "sessionId", "ip",
                "user_id", "userId", "attributes"
        );

        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            if (!knownFields.contains(key)) {
                attributes.put(key, entry.getValue());
            }
        }

        builder.attributes(attributes);
        return builder.build();
    }

    /**
     * RiskEvent转JSON字符串
     */
    public String toJson(RiskEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.error("RiskEvent序列化JSON失败, eventId: {}", event.getEventId(), e);
            throw new RuntimeException("事件JSON序列化失败", e);
        }
    }

    /**
     * RiskEvent转Map
     */
    public Map<String, Object> toMap(RiskEvent event) {
        return objectMapper.convertValue(event, new TypeReference<>() {});
    }

    /**
     * 从Map中安全获取字符串值（支持多个候选键名）
     */
    private String getString(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                return value.toString();
            }
        }
        return null;
    }

    /**
     * 从Map中安全获取Long值
     */
    private Long getLong(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value == null) {
                continue;
            }
            if (value instanceof Number number) {
                return number.longValue();
            }
            try {
                return Long.parseLong(value.toString().trim());
            } catch (NumberFormatException e) {
                throw new EventValidationException("字段[" + key + "]必须为整数类型: " + value);
            }
        }
        return null;
    }
}
