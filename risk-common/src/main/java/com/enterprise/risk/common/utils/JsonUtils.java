package com.enterprise.risk.common.utils;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Jackson JSON工具封装
 * 提供JSON序列化、反序列化、节点操作等常用功能
 */
@Slf4j
public class JsonUtils {

    private static final ObjectMapper OBJECT_MAPPER = createObjectMapper();

    private static final String STANDARD_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";

    private JsonUtils() {
    }

    /**
     * 创建并配置ObjectMapper实例
     */
    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        SimpleDateFormat dateFormat = new SimpleDateFormat(STANDARD_DATE_FORMAT);
        dateFormat.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        mapper.setDateFormat(dateFormat);
        return mapper;
    }

    /**
     * 获取全局ObjectMapper实例
     *
     * @return ObjectMapper实例
     */
    public static ObjectMapper getObjectMapper() {
        return OBJECT_MAPPER;
    }

    /**
     * 将对象序列化为JSON字符串
     *
     * @param obj 待序列化对象
     * @return JSON字符串，序列化失败返回null
     */
    public static String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("JSON序列化失败", e);
            return null;
        }
    }

    /**
     * 将对象序列化为格式化的JSON字符串（带缩进）
     *
     * @param obj 待序列化对象
     * @return 格式化的JSON字符串
     */
    public static String toPrettyJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("JSON格式化序列化失败", e);
            return null;
        }
    }

    /**
     * 将对象序列化为字节数组
     *
     * @param obj 待序列化对象
     * @return JSON字节数组
     */
    public static byte[] toJsonBytes(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsBytes(obj);
        } catch (JsonProcessingException e) {
            log.error("JSON字节序列化失败", e);
            return null;
        }
    }

    /**
     * 将JSON字符串反序列化为指定类型对象
     *
     * @param json JSON字符串
     * @param clazz 目标类型
     * @param <T> 泛型类型
     * @return 反序列化对象，失败返回null
     */
    public static <T> T fromJson(String json, Class<T> clazz) {
        if (json == null || json.isEmpty() || clazz == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            log.error("JSON反序列化失败, 目标类型: {}", clazz.getName(), e);
            return null;
        }
    }

    /**
     * 将JSON字符串反序列化为指定类型（使用TypeReference支持泛型）
     *
     * @param json JSON字符串
     * @param typeReference 类型引用
     * @param <T> 泛型类型
     * @return 反序列化对象
     */
    public static <T> T fromJson(String json, TypeReference<T> typeReference) {
        if (json == null || json.isEmpty() || typeReference == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, typeReference);
        } catch (JsonProcessingException e) {
            log.error("JSON反序列化失败（TypeReference）", e);
            return null;
        }
    }

    /**
     * 将JSON字节数组反序列化为指定类型
     *
     * @param bytes JSON字节数组
     * @param clazz 目标类型
     * @param <T> 泛型类型
     * @return 反序列化对象
     */
    public static <T> T fromJsonBytes(byte[] bytes, Class<T> clazz) {
        if (bytes == null || bytes.length == 0 || clazz == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(bytes, clazz);
        } catch (IOException e) {
            log.error("JSON字节反序列化失败, 目标类型: {}", clazz.getName(), e);
            return null;
        }
    }

    /**
     * 将JSON字节数组反序列化为指定类型（使用TypeReference）
     *
     * @param bytes JSON字节数组
     * @param typeReference 类型引用
     * @param <T> 泛型类型
     * @return 反序列化对象
     */
    public static <T> T fromJsonBytes(byte[] bytes, TypeReference<T> typeReference) {
        if (bytes == null || bytes.length == 0 || typeReference == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(bytes, typeReference);
        } catch (IOException e) {
            log.error("JSON字节反序列化失败（TypeReference）", e);
            return null;
        }
    }

    /**
     * 从输入流读取JSON并反序列化
     *
     * @param inputStream 输入流
     * @param clazz 目标类型
     * @param <T> 泛型类型
     * @return 反序列化对象
     */
    public static <T> T fromStream(InputStream inputStream, Class<T> clazz) {
        if (inputStream == null || clazz == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(inputStream, clazz);
        } catch (IOException e) {
            log.error("JSON流反序列化失败, 目标类型: {}", clazz.getName(), e);
            return null;
        }
    }

    /**
     * 从文件读取JSON并反序列化
     *
     * @param file 文件
     * @param clazz 目标类型
     * @param <T> 泛型类型
     * @return 反序列化对象
     */
    public static <T> T fromFile(File file, Class<T> clazz) {
        if (file == null || !file.exists() || clazz == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(file, clazz);
        } catch (IOException e) {
            log.error("JSON文件反序列化失败, 文件: {}, 目标类型: {}", file.getAbsolutePath(), clazz.getName(), e);
            return null;
        }
    }

    /**
     * 将对象转换为Map
     *
     * @param obj 待转换对象
     * @return Map对象
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof Map) {
            return (Map<String, Object>) obj;
        }
        return OBJECT_MAPPER.convertValue(obj, new TypeReference<Map<String, Object>>() {});
    }

    /**
     * 将Map转换为指定类型对象
     *
     * @param map Map对象
     * @param clazz 目标类型
     * @param <T> 泛型类型
     * @return 转换后的对象
     */
    public static <T> T fromMap(Map<String, Object> map, Class<T> clazz) {
        if (map == null || clazz == null) {
            return null;
        }
        return OBJECT_MAPPER.convertValue(map, clazz);
    }

    /**
     * 将JSON字符串转换为List
     *
     * @param json JSON字符串
     * @param elementClass 元素类型
     * @param <T> 泛型类型
     * @return List对象
     */
    public static <T> List<T> toList(String json, Class<T> elementClass) {
        if (json == null || json.isEmpty() || elementClass == null) {
            return null;
        }
        try {
            JavaType javaType = OBJECT_MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, elementClass);
            return OBJECT_MAPPER.readValue(json, javaType);
        } catch (JsonProcessingException e) {
            log.error("JSON转List失败, 元素类型: {}", elementClass.getName(), e);
            return null;
        }
    }

    /**
     * 将JSON字符串转换为List<Map>
     *
     * @param json JSON字符串
     * @return List<Map>对象
     */
    public static List<Map<String, Object>> toListOfMaps(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        return fromJson(json, new TypeReference<List<Map<String, Object>>>() {});
    }

    /**
     * 将JSON字符串转换为Map
     *
     * @param json JSON字符串
     * @return Map对象
     */
    public static Map<String, Object> toMap(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        return fromJson(json, new TypeReference<Map<String, Object>>() {});
    }

    /**
     * 解析JSON字符串为JsonNode
     *
     * @param json JSON字符串
     * @return JsonNode
     */
    public static JsonNode parseTree(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            log.error("JSON解析为树失败", e);
            return null;
        }
    }

    /**
     * 解析JSON字节数组为JsonNode
     *
     * @param bytes JSON字节数组
     * @return JsonNode
     */
    public static JsonNode parseTree(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readTree(bytes);
        } catch (IOException e) {
            log.error("JSON字节解析为树失败", e);
            return null;
        }
    }

    /**
     * 创建一个空的ObjectNode
     *
     * @return ObjectNode
     */
    public static ObjectNode createObjectNode() {
        return OBJECT_MAPPER.createObjectNode();
    }

    /**
     * 创建一个空的ArrayNode
     *
     * @return ArrayNode
     */
    public static ArrayNode createArrayNode() {
        return OBJECT_MAPPER.createArrayNode();
    }

    /**
     * 从JsonNode中获取字符串值
     *
     * @param node JsonNode节点
     * @param fieldName 字段名
     * @param defaultValue 默认值
     * @return 字符串值
     */
    public static String getText(JsonNode node, String fieldName, String defaultValue) {
        if (node == null || fieldName == null) {
            return defaultValue;
        }
        JsonNode fieldNode = node.get(fieldName);
        if (fieldNode == null || fieldNode.isNull()) {
            return defaultValue;
        }
        return fieldNode.asText(defaultValue);
    }

    /**
     * 从JsonNode中获取整数值
     *
     * @param node JsonNode节点
     * @param fieldName 字段名
     * @param defaultValue 默认值
     * @return 整数值
     */
    public static Integer getInt(JsonNode node, String fieldName, Integer defaultValue) {
        if (node == null || fieldName == null) {
            return defaultValue;
        }
        JsonNode fieldNode = node.get(fieldName);
        if (fieldNode == null || fieldNode.isNull() || !fieldNode.canConvertToInt()) {
            return defaultValue;
        }
        return fieldNode.asInt();
    }

    /**
     * 从JsonNode中获取长整型值
     *
     * @param node JsonNode节点
     * @param fieldName 字段名
     * @param defaultValue 默认值
     * @return 长整型值
     */
    public static Long getLong(JsonNode node, String fieldName, Long defaultValue) {
        if (node == null || fieldName == null) {
            return defaultValue;
        }
        JsonNode fieldNode = node.get(fieldName);
        if (fieldNode == null || fieldNode.isNull() || !fieldNode.canConvertToLong()) {
            return defaultValue;
        }
        return fieldNode.asLong();
    }

    /**
     * 从JsonNode中获取布尔值
     *
     * @param node JsonNode节点
     * @param fieldName 字段名
     * @param defaultValue 默认值
     * @return 布尔值
     */
    public static Boolean getBoolean(JsonNode node, String fieldName, Boolean defaultValue) {
        if (node == null || fieldName == null) {
            return defaultValue;
        }
        JsonNode fieldNode = node.get(fieldName);
        if (fieldNode == null || fieldNode.isNull()) {
            return defaultValue;
        }
        return fieldNode.asBoolean(defaultValue);
    }

    /**
     * 克隆对象（深拷贝）
     *
     * @param obj 待克隆对象
     * @param clazz 对象类型
     * @param <T> 泛型类型
     * @return 克隆后的对象
     */
    public static <T> T clone(T obj, Class<T> clazz) {
        if (obj == null || clazz == null) {
            return null;
        }
        String json = toJson(obj);
        return fromJson(json, clazz);
    }

    /**
     * 校验是否为合法JSON
     *
     * @param json 待校验字符串
     * @return 是否合法JSON
     */
    public static boolean isValidJson(String json) {
        if (json == null || json.isEmpty()) {
            return false;
        }
        try {
            OBJECT_MAPPER.readTree(json);
            return true;
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    /**
     * 将一个对象的属性合并到另一个对象
     *
     * @param source 源对象
     * @param target 目标对象
     * @param <T> 泛型类型
     * @return 合并后的目标对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T merge(T source, T target) {
        if (source == null || target == null) {
            return target;
        }
        Map<String, Object> sourceMap = toMap(source);
        Map<String, Object> targetMap = toMap(target);
        if (sourceMap != null && targetMap != null) {
            sourceMap.forEach(targetMap::putIfAbsent);
            return (T) fromMap(targetMap, target.getClass());
        }
        return target;
    }
}
