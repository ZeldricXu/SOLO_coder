package com.enterprise.risk.gateway.deserializer;

import com.enterprise.risk.common.event.RiskEvent;
import com.enterprise.risk.common.exception.EventValidationException;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 统一事件反序列化器
 * 自动识别并处理两种格式：
 * 1. Content-Type: application/json -> JSON格式
 * 2. Content-Type: application/x-protobuf -> Protobuf格式
 *
 * 作为反序列化模块的门面（Facade），对外提供统一的反序列化入口
 */
@Slf4j
@Component
public class RiskEventDeserializer {

    private final JsonEventConverter jsonConverter;
    private final ProtobufEventConverter protobufConverter;

    public RiskEventDeserializer(JsonEventConverter jsonConverter,
                                 ProtobufEventConverter protobufConverter) {
        this.jsonConverter = jsonConverter;
        this.protobufConverter = protobufConverter;
    }

    /**
     * 反序列化JSON单条事件
     *
     * @param json JSON字符串
     * @return RiskEvent对象
     * @throws EventValidationException 反序列化失败时抛出
     */
    public RiskEvent deserializeJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            throw new EventValidationException("JSON请求体不能为空");
        }

        try {
            RiskEvent event = jsonConverter.fromJson(json);
            log.debug("JSON反序列化成功, eventId: {}, eventType: {}",
                    event.getEventId(), event.getEventType());
            return event;
        } catch (EventValidationException e) {
            throw e;
        } catch (Exception e) {
            log.error("JSON反序列化失败, 数据长度: {}", json.length(), e);
            throw new EventValidationException("JSON格式错误: " + e.getMessage(), json);
        }
    }

    /**
     * 反序列化JSON批量事件
     *
     * @param json JSON数组字符串
     * @return RiskEvent列表
     * @throws EventValidationException 反序列化失败时抛出
     */
    public List<RiskEvent> deserializeJsonBatch(String json) {
        if (json == null || json.trim().isEmpty()) {
            throw new EventValidationException("批量JSON请求体不能为空");
        }

        try {
            List<RiskEvent> events = jsonConverter.fromJsonBatch(json);
            log.debug("JSON批量反序列化成功, 事件数量: {}", events.size());
            return events;
        } catch (EventValidationException e) {
            throw e;
        } catch (Exception e) {
            log.error("JSON批量反序列化失败, 数据长度: {}", json.length(), e);
            throw new EventValidationException("批量JSON格式错误: " + e.getMessage(), json);
        }
    }

    /**
     * 反序列化Protobuf事件
     *
     * @param bytes Protobuf二进制数据
     * @return RiskEvent对象
     * @throws EventValidationException 反序列化失败时抛出
     */
    public RiskEvent deserializeProtobuf(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new EventValidationException("Protobuf请求体不能为空");
        }

        try {
            RiskEvent event = protobufConverter.fromProtobuf(bytes);
            log.debug("Protobuf反序列化成功, eventId: {}, eventType: {}",
                    event.getEventId(), event.getEventType());
            return event;
        } catch (EventValidationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Protobuf反序列化失败, 数据长度: {} bytes", bytes.length, e);
            throw new EventValidationException("Protobuf格式错误: " + e.getMessage());
        }
    }

    /**
     * 自动识别Content-Type并反序列化
     *
     * @param contentType Content-Type头
     * @param body        原始请求体（byte[]或String）
     * @return RiskEvent对象
     */
    public RiskEvent deserializeAuto(String contentType, Object body) {
        boolean isProtobuf = (contentType != null &&
                (contentType.contains("protobuf") || contentType.contains("x-protobuf")));

        if (body instanceof byte[] bytes) {
            if (isProtobuf) {
                return deserializeProtobuf(bytes);
            } else {
                return deserializeJson(new String(bytes));
            }
        } else if (body instanceof String str) {
            if (isProtobuf) {
                return deserializeProtobuf(str.getBytes());
            } else {
                return deserializeJson(str);
            }
        } else if (body instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) body;
            return jsonConverter.fromMap(map);
        }

        throw new EventValidationException("不支持的请求体类型: " +
                (body != null ? body.getClass().getName() : "null"));
    }

    /**
     * 将Map转换为RiskEvent（便捷方法）
     */
    public RiskEvent deserializeMap(Map<String, Object> map) {
        return jsonConverter.fromMap(map);
    }

    /**
     * 将RiskEvent序列化为JSON字符串
     */
    public String serializeToJson(RiskEvent event) {
        return jsonConverter.toJson(event);
    }

    /**
     * 将RiskEvent序列化为Protobuf字节数组
     */
    public byte[] serializeToProtobuf(RiskEvent event) {
        return protobufConverter.toProtobuf(event);
    }
}
