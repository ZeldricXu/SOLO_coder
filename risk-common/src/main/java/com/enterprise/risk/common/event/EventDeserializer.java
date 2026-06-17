package com.enterprise.risk.common.event;

import com.enterprise.risk.common.exception.RiskException;
import com.enterprise.risk.common.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Protobuf/JSON双格式反序列化器
 * 支持自动检测数据格式，统一反序列化为RiskEvent对象
 */
@Slf4j
public class EventDeserializer {

    /**
     * 数据格式类型
     */
    public enum DataFormat {
        JSON,
        PROTOBUF,
        AUTO_DETECT
    }

    /**
     * 反序列化结果
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeserializeResult implements Serializable {
        private RiskEvent event;
        private DataFormat format;
        private Long deserializeTimeMs;
        private Boolean success;
        private String errorMessage;
        private Integer payloadSizeBytes;

        public boolean isSuccess() {
            return Boolean.TRUE.equals(success);
        }
    }

    /**
     * Protobuf消息解析器（用于扩展自定义Protobuf类型）
     */
    @FunctionalInterface
    public interface ProtobufParser {
        Message parseFrom(byte[] data) throws InvalidProtocolBufferException;
    }

    private static final int PROTOBUF_DETECTION_BYTES = 16;

    private static final JsonFormat.Printer PROTOBUF_JSON_PRINTER = JsonFormat.printer()
            .omittingInsignificantWhitespace()
            .includingDefaultValueFields();

    private static final JsonFormat.Parser PROTOBUF_JSON_PARSER = JsonFormat.parser()
            .ignoringUnknownFields();

    private EventDeserializer() {
    }

    /**
     * 自动检测格式并反序列化
     *
     * @param data 原始字节数据
     * @return 反序列化结果
     */
    public static DeserializeResult deserialize(byte[] data) {
        return deserialize(data, DataFormat.AUTO_DETECT, null);
    }

    /**
     * 指定格式反序列化
     *
     * @param data 原始字节数据
     * @param format 数据格式
     * @return 反序列化结果
     */
    public static DeserializeResult deserialize(byte[] data, DataFormat format) {
        return deserialize(data, format, null);
    }

    /**
     * 完整反序列化方法
     *
     * @param data 原始字节数据
     * @param format 数据格式
     * @param protobufParser Protobuf解析器（可为null）
     * @return 反序列化结果
     */
    public static DeserializeResult deserialize(byte[] data, DataFormat format, ProtobufParser protobufParser) {
        long startTime = System.currentTimeMillis();
        DeserializeResult.DeserializeResultBuilder resultBuilder = DeserializeResult.builder()
                .payloadSizeBytes(data != null ? data.length : 0);

        if (data == null || data.length == 0) {
            return resultBuilder
                    .success(false)
                    .errorMessage("数据为空")
                    .deserializeTimeMs(System.currentTimeMillis() - startTime)
                    .build();
        }

        try {
            DataFormat detectedFormat = format;
            if (format == DataFormat.AUTO_DETECT) {
                detectedFormat = detectFormat(data);
            }
            resultBuilder.format(detectedFormat);

            RiskEvent event;
            if (detectedFormat == DataFormat.PROTOBUF) {
                event = deserializeFromProtobuf(data, protobufParser);
            } else {
                event = deserializeFromJson(data);
            }

            return resultBuilder
                    .event(event)
                    .success(true)
                    .deserializeTimeMs(System.currentTimeMillis() - startTime)
                    .build();

        } catch (Exception e) {
            log.error("事件反序列化失败, 尝试格式: {}", format, e);
            return resultBuilder
                    .success(false)
                    .errorMessage(e.getMessage())
                    .deserializeTimeMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    /**
     * 从JSON字符串反序列化
     *
     * @param jsonString JSON字符串
     * @return 反序列化结果
     */
    public static DeserializeResult deserializeFromJsonString(String jsonString) {
        long startTime = System.currentTimeMillis();
        byte[] data = jsonString != null ? jsonString.getBytes(StandardCharsets.UTF_8) : new byte[0];
        DeserializeResult.DeserializeResultBuilder resultBuilder = DeserializeResult.builder()
                .format(DataFormat.JSON)
                .payloadSizeBytes(data.length);

        try {
            RiskEvent event = deserializeFromJson(data);
            return resultBuilder
                    .event(event)
                    .success(true)
                    .deserializeTimeMs(System.currentTimeMillis() - startTime)
                    .build();
        } catch (Exception e) {
            log.error("JSON字符串反序列化失败", e);
            return resultBuilder
                    .success(false)
                    .errorMessage(e.getMessage())
                    .deserializeTimeMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    /**
     * 从输入流反序列化
     *
     * @param inputStream 输入流
     * @return 反序列化结果
     */
    public static DeserializeResult deserializeFromStream(InputStream inputStream) {
        return deserializeFromStream(inputStream, DataFormat.AUTO_DETECT, null);
    }

    /**
     * 从输入流反序列化（指定格式）
     *
     * @param inputStream 输入流
     * @param format 数据格式
     * @param protobufParser Protobuf解析器
     * @return 反序列化结果
     */
    public static DeserializeResult deserializeFromStream(InputStream inputStream, DataFormat format,
                                                           ProtobufParser protobufParser) {
        try {
            byte[] data = readAllBytes(inputStream);
            return deserialize(data, format, protobufParser);
        } catch (IOException e) {
            log.error("读取输入流失败", e);
            return DeserializeResult.builder()
                    .success(false)
                    .errorMessage("读取输入流失败: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 检测数据格式
     * 基于首字节特征进行启发式判断
     *
     * @param data 原始字节数据
     * @return 检测到的格式
     */
    public static DataFormat detectFormat(byte[] data) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("数据为空");
        }

        int sampleSize = Math.min(data.length, PROTOBUF_DETECTION_BYTES);

        byte firstByte = data[0];
        if (firstByte == '{' || firstByte == '[' || firstByte == '"') {
            return DataFormat.JSON;
        }

        for (int i = 0; i < sampleSize; i++) {
            byte b = data[i];
            if (b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D) {
                if (b == 0x00 && i > 0) {
                    return DataFormat.PROTOBUF;
                }
            }
            if (b >= 0x7F) {
                return DataFormat.PROTOBUF;
            }
        }

        String sample = new String(data, 0, sampleSize, StandardCharsets.UTF_8).trim();
        if (!sample.isEmpty() && (sample.startsWith("{") || sample.startsWith("["))) {
            return DataFormat.JSON;
        }

        return isValidJsonStart(data) ? DataFormat.JSON : DataFormat.PROTOBUF;
    }

    /**
     * 将RiskEvent序列化为JSON字节
     *
     * @param event 风险事件
     * @return JSON字节数组
     */
    public static byte[] serializeToJsonBytes(RiskEvent event) {
        if (event == null) {
            return new byte[0];
        }
        String json = JsonUtils.toJson(event);
        return json != null ? json.getBytes(StandardCharsets.UTF_8) : new byte[0];
    }

    /**
     * 将RiskEvent序列化为JSON字符串
     *
     * @param event 风险事件
     * @return JSON字符串
     */
    public static String serializeToJsonString(RiskEvent event) {
        return JsonUtils.toJson(event);
    }

    /**
     * 将Protobuf消息转换为RiskEvent
     *
     * @param message Protobuf消息
     * @return RiskEvent对象
     */
    public static RiskEvent convertProtobufToEvent(MessageOrBuilder message) {
        try {
            String json = PROTOBUF_JSON_PRINTER.print(message);
            return deserializeFromJson(json.getBytes(StandardCharsets.UTF_8));
        } catch (InvalidProtocolBufferException e) {
            log.error("Protobuf转JSON失败", e);
            throw new RiskException("Protobuf转换失败", e);
        }
    }

    /**
     * 将RiskEvent转换为Protobuf Builder
     *
     * @param event 风险事件
     * @param builder Protobuf Builder实例
     * @return 填充后的Builder
     */
    @SuppressWarnings("unchecked")
    public static <T extends Message.Builder> T convertEventToProtobufBuilder(RiskEvent event, T builder) {
        try {
            String json = serializeToJsonString(event);
            if (json != null) {
                PROTOBUF_JSON_PARSER.merge(json, builder);
            }
            return builder;
        } catch (InvalidProtocolBufferException e) {
            log.error("JSON转Protobuf失败", e);
            throw new RiskException("JSON转Protobuf失败", e);
        }
    }

    /**
     * 从JSON字节反序列化为RiskEvent
     */
    private static RiskEvent deserializeFromJson(byte[] data) {
        JsonNode rootNode = JsonUtils.parseTree(data);
        if (rootNode == null) {
            throw new RiskException("JSON解析失败");
        }

        RiskEvent.RiskEventBuilder builder = RiskEvent.builder();

        if (rootNode.has("event_id")) {
            builder.eventId(rootNode.get("event_id").asText(null));
        }
        if (rootNode.has("event_type")) {
            builder.eventType(rootNode.get("event_type").asText(null));
        }
        if (rootNode.has("business_line")) {
            builder.businessLine(rootNode.get("business_line").asText(null));
        }
        if (rootNode.has("timestamp")) {
            JsonNode tsNode = rootNode.get("timestamp");
            if (tsNode.isNumber()) {
                builder.timestamp(tsNode.asLong());
            } else if (tsNode.isTextual()) {
                builder.timestamp(parseTimestamp(tsNode.asText()));
            }
        }
        if (rootNode.has("entity_id")) {
            builder.entityId(rootNode.get("entity_id").asText(null));
        }
        if (rootNode.has("entity_type")) {
            builder.entityType(rootNode.get("entity_type").asText(null));
        }
        if (rootNode.has("source")) {
            builder.source(rootNode.get("source").asText(null));
        }
        if (rootNode.has("session_id")) {
            builder.sessionId(rootNode.get("session_id").asText(null));
        }
        if (rootNode.has("ip")) {
            builder.ip(rootNode.get("ip").asText(null));
        }
        if (rootNode.has("user_id")) {
            builder.userId(rootNode.get("user_id").asText(null));
        }

        Map<String, Object> attributes = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = rootNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            if (!isTopLevelField(key)) {
                attributes.put(key, jsonNodeToValue(entry.getValue()));
            }
        }
        builder.attributes(attributes);

        return builder.build();
    }

    /**
     * 从Protobuf字节反序列化为RiskEvent
     */
    private static RiskEvent deserializeFromProtobuf(byte[] data, ProtobufParser protobufParser) {
        if (protobufParser != null) {
            try {
                Message message = protobufParser.parseFrom(data);
                return convertProtobufToEvent(message);
            } catch (InvalidProtocolBufferException e) {
                log.warn("自定义Protobuf解析失败，尝试JSON降级", e);
            }
        }
        return deserializeFromJson(data);
    }

    /**
     * 检查是否为顶层字段
     */
    private static boolean isTopLevelField(String key) {
        switch (key) {
            case "event_id":
            case "event_type":
            case "business_line":
            case "timestamp":
            case "entity_id":
            case "entity_type":
            case "source":
            case "session_id":
            case "ip":
            case "user_id":
                return true;
            default:
                return false;
        }
    }

    /**
     * JsonNode转换为Java对象
     */
    private static Object jsonNodeToValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isInt()) {
            return node.asInt();
        }
        if (node.isLong()) {
            return node.asLong();
        }
        if (node.isDouble() || node.isFloat()) {
            return node.asDouble();
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            java.util.List<Object> list = new java.util.ArrayList<>();
            for (JsonNode element : node) {
                list.add(jsonNodeToValue(element));
            }
            return list;
        }
        if (node.isObject()) {
            Map<String, Object> map = new HashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                map.put(entry.getKey(), jsonNodeToValue(entry.getValue()));
            }
            return map;
        }
        return node.asText();
    }

    /**
     * 解析时间戳字符串
     */
    private static Long parseTimestamp(String timestampStr) {
        if (timestampStr == null || timestampStr.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(timestampStr);
        } catch (NumberFormatException e) {
            try {
                return java.time.Instant.parse(timestampStr).toEpochMilli();
            } catch (Exception ex) {
                log.warn("时间戳解析失败: {}", timestampStr);
                return System.currentTimeMillis();
            }
        }
    }

    /**
     * 验证JSON起始格式
     */
    private static boolean isValidJsonStart(byte[] data) {
        for (byte b : data) {
            if (b == ' ' || b == '\t' || b == '\n' || b == '\r') {
                continue;
            }
            return b == '{' || b == '[';
        }
        return false;
    }

    /**
     * 读取输入流的所有字节
     */
    private static byte[] readAllBytes(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return new byte[0];
        }
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] temp = new byte[8192];
        int bytesRead;
        while ((bytesRead = inputStream.read(temp)) != -1) {
            buffer.write(temp, 0, bytesRead);
        }
        return buffer.toByteArray();
    }
}
