package com.enterprise.risk.gateway.deserializer;

import com.enterprise.risk.common.event.RiskEvent;
import com.enterprise.risk.common.exception.EventValidationException;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Protobuf事件转换器
 * 负责Protobuf二进制数据与RiskEvent对象之间的相互转换
 *
 * 处理Protobuf中各种类型attributes的映射：
 * - attributes_str:   Map<String, String>  -> 直接存入attributes
 * - attributes_long:  Map<String, Long>    -> 直接存入attributes
 * - attributes_double: Map<String, Double>  -> 直接存入attributes
 * - attributes_bool:  Map<String, Boolean> -> 直接存入attributes
 */
@Slf4j
@Component
public class ProtobufEventConverter {

    private static final String PROTO_CLASS_NAME = "com.enterprise.risk.common.event.RiskEventProto";
    private static final String EVENT_BATCH_CLASS_NAME = "com.enterprise.risk.common.event.EventBatch";

    private volatile Class<?> protoClass;
    private volatile Class<?> batchClass;
    private volatile boolean protoAvailable = true;

    /**
     * Protobuf字节数组转RiskEvent对象
     */
    public RiskEvent fromProtobuf(byte[] bytes) {
        try {
            Object protoMessage = parseProto(bytes);
            return convertFromProto(protoMessage);
        } catch (EventValidationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Protobuf解析失败", e);
            throw new EventValidationException("Protobuf解析失败: " + e.getMessage());
        }
    }

    /**
     * RiskEvent对象转Protobuf字节数组
     */
    public byte[] toProtobuf(RiskEvent event) {
        try {
            Object protoMessage = convertToProto(event);
            return serializeProto(protoMessage);
        } catch (Exception e) {
            log.error("RiskEvent转Protobuf失败, eventId: {}", event.getEventId(), e);
            throw new RuntimeException("Protobuf序列化失败", e);
        }
    }

    /**
     * 解析Protobuf二进制数据
     */
    private Object parseProto(byte[] bytes) throws Exception {
        Class<?> clazz = getProtoClass();
        Method parseMethod = clazz.getMethod("parseFrom", byte[].class);
        return parseMethod.invoke(null, (Object) bytes);
    }

    /**
     * 序列化Protobuf消息为字节数组
     */
    private byte[] serializeProto(Object protoMessage) throws Exception {
        Method toByteArrayMethod = protoMessage.getClass().getMethod("toByteArray");
        return (byte[]) toByteArrayMethod.invoke(protoMessage);
    }

    /**
     * Protobuf消息对象转RiskEvent
     */
    @SuppressWarnings("unchecked")
    private RiskEvent convertFromProto(Object proto) throws Exception {
        Class<?> clazz = proto.getClass();

        RiskEvent.RiskEventBuilder builder = RiskEvent.builder();

        String eventId = invokeStringGetter(clazz, proto, "getEventId");
        if (eventId == null || eventId.isEmpty()) {
            eventId = UUID.randomUUID().toString();
        }
        builder.eventId(eventId);

        String eventType = invokeStringGetter(clazz, proto, "getEventType");
        if (eventType != null && !eventType.isEmpty()) {
            builder.eventType(eventType);
        }

        String businessLine = invokeStringGetter(clazz, proto, "getBusinessLine");
        if (businessLine != null && !businessLine.isEmpty()) {
            builder.businessLine(businessLine);
        }

        long timestamp = invokeLongGetter(clazz, proto, "getTimestamp");
        if (timestamp <= 0) {
            timestamp = Instant.now().toEpochMilli();
        }
        builder.timestamp(timestamp);

        String entityId = invokeStringGetter(clazz, proto, "getEntityId");
        if (entityId != null && !entityId.isEmpty()) {
            builder.entityId(entityId);
        }

        String entityType = invokeStringGetter(clazz, proto, "getEntityType");
        if (entityType != null && !entityType.isEmpty()) {
            builder.entityType(entityType);
        }

        String source = invokeStringGetter(clazz, proto, "getSource");
        if (source != null && !source.isEmpty()) {
            builder.source(source);
        }

        String sessionId = invokeStringGetter(clazz, proto, "getSessionId");
        if (sessionId != null && !sessionId.isEmpty()) {
            builder.sessionId(sessionId);
        }

        String ip = invokeStringGetter(clazz, proto, "getIp");
        if (ip != null && !ip.isEmpty()) {
            builder.ip(ip);
        }

        String userId = invokeStringGetter(clazz, proto, "getUserId");
        if (userId != null && !userId.isEmpty()) {
            builder.userId(userId);
        }

        Map<String, Object> attributes = new HashMap<>();

        Map<String, String> strAttrs = (Map<String, String>) invokeMapGetter(clazz, proto, "getAttributesStrMap");
        if (strAttrs != null) {
            attributes.putAll(strAttrs);
        }

        Map<String, Long> longAttrs = (Map<String, Long>) invokeMapGetter(clazz, proto, "getAttributesLongMap");
        if (longAttrs != null) {
            attributes.putAll(longAttrs);
        }

        Map<String, Double> doubleAttrs = (Map<String, Double>) invokeMapGetter(clazz, proto, "getAttributesDoubleMap");
        if (doubleAttrs != null) {
            attributes.putAll(doubleAttrs);
        }

        Map<String, Boolean> boolAttrs = (Map<String, Boolean>) invokeMapGetter(clazz, proto, "getAttributesBoolMap");
        if (boolAttrs != null) {
            attributes.putAll(boolAttrs);
        }

        builder.attributes(attributes);
        return builder.build();
    }

    /**
     * RiskEvent转Protobuf消息对象
     */
    @SuppressWarnings("unchecked")
    private Object convertToProto(RiskEvent event) throws Exception {
        Class<?> clazz = getProtoClass();

        Object builder = invokeMethod(clazz, null, "newBuilder");
        Class<?> builderClass = builder.getClass();

        setStringField(builderClass, builder, "setEventId", event.getEventId());
        setStringField(builderClass, builder, "setEventType", event.getEventType());
        setStringField(builderClass, builder, "setBusinessLine", event.getBusinessLine());
        setLongField(builderClass, builder, "setTimestamp", event.getTimestamp());
        setStringField(builderClass, builder, "setEntityId", event.getEntityId());
        setStringField(builderClass, builder, "setEntityType", event.getEntityType());
        setStringField(builderClass, builder, "setSource", event.getSource());
        setStringField(builderClass, builder, "setSessionId", event.getSessionId());
        setStringField(builderClass, builder, "setIp", event.getIp());
        setStringField(builderClass, builder, "setUserId", event.getUserId());

        if (event.getAttributes() != null) {
            for (Map.Entry<String, Object> entry : event.getAttributes().entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (value == null) continue;

                if (value instanceof String s) {
                    invokePutMethod(builderClass, builder, "putAttributesStr", key, s);
                } else if (value instanceof Integer || value instanceof Long) {
                    invokePutMethod(builderClass, builder, "putAttributesLong", key, ((Number) value).longValue());
                } else if (value instanceof Double || value instanceof Float) {
                    invokePutMethod(builderClass, builder, "putAttributesDouble", key, ((Number) value).doubleValue());
                } else if (value instanceof Boolean b) {
                    invokePutMethod(builderClass, builder, "putAttributesBool", key, b);
                } else {
                    invokePutMethod(builderClass, builder, "putAttributesStr", key, value.toString());
                }
            }
        }

        return invokeMethod(builderClass, builder, "build");
    }

    private Class<?> getProtoClass() throws ClassNotFoundException {
        if (protoClass == null) {
            synchronized (this) {
                if (protoClass == null) {
                    protoClass = Class.forName(PROTO_CLASS_NAME);
                }
            }
        }
        return protoClass;
    }

    private String invokeStringGetter(Class<?> clazz, Object obj, String methodName) throws Exception {
        try {
            Method method = clazz.getMethod(methodName);
            Object result = method.invoke(obj);
            return result != null ? result.toString() : null;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private long invokeLongGetter(Class<?> clazz, Object obj, String methodName) throws Exception {
        try {
            Method method = clazz.getMethod(methodName);
            Object result = method.invoke(obj);
            return result instanceof Number ? ((Number) result).longValue() : 0L;
        } catch (NoSuchMethodException e) {
            return 0L;
        }
    }

    private Object invokeMapGetter(Class<?> clazz, Object obj, String methodName) throws Exception {
        try {
            Method method = clazz.getMethod(methodName);
            return method.invoke(obj);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private Object invokeMethod(Class<?> clazz, Object obj, String methodName, Object... args) throws Exception {
        Class<?>[] paramTypes = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            paramTypes[i] = args[i].getClass();
        }
        Method method = findMethod(clazz, methodName, paramTypes);
        return method.invoke(obj, args);
    }

    private Method findMethod(Class<?> clazz, String methodName, Class<?>[] paramTypes) throws NoSuchMethodException {
        try {
            return clazz.getMethod(methodName, paramTypes);
        } catch (NoSuchMethodException e) {
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals(methodName) && m.getParameterCount() == paramTypes.length) {
                    return m;
                }
            }
            throw e;
        }
    }

    private void setStringField(Class<?> clazz, Object obj, String methodName, String value) throws Exception {
        if (value == null) return;
        try {
            Method method = clazz.getMethod(methodName, String.class);
            method.invoke(obj, value);
        } catch (NoSuchMethodException ignored) {
        }
    }

    private void setLongField(Class<?> clazz, Object obj, String methodName, Long value) throws Exception {
        if (value == null) return;
        try {
            Method method = clazz.getMethod(methodName, long.class);
            method.invoke(obj, value);
        } catch (NoSuchMethodException ignored) {
        }
    }

    private void invokePutMethod(Class<?> clazz, Object obj, String methodName, String key, Object value) throws Exception {
        try {
            Class<?> valueClass = value.getClass();
            if (valueClass == Integer.class) valueClass = long.class;
            if (valueClass == Long.class) valueClass = long.class;
            if (valueClass == Double.class) valueClass = double.class;
            if (valueClass == Float.class) valueClass = double.class;
            if (valueClass == Boolean.class) valueClass = boolean.class;

            Method method = clazz.getMethod(methodName, String.class, valueClass);
            method.invoke(obj, key, value);
        } catch (NoSuchMethodException ignored) {
        }
    }
}
