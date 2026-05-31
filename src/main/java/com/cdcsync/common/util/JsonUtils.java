package com.cdcsync.common.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.TypeReference;

import java.util.List;
import java.util.Map;

public class JsonUtils {

    private JsonUtils() {
    }

    public static String toJson(Object obj) {
        return JSON.toJSONString(obj);
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        return JSON.parseObject(json, clazz);
    }

    public static <T> T fromJson(String json, TypeReference<T> typeReference) {
        return JSON.parseObject(json, typeReference);
    }

    public static <T> List<T> fromJsonList(String json, Class<T> clazz) {
        return JSON.parseArray(json, clazz);
    }

    public static Map<String, Object> toMap(Object obj) {
        return JSON.parseObject(toJson(obj), new TypeReference<Map<String, Object>>() {});
    }

    public static <T> T mapToObject(Map<String, Object> map, Class<T> clazz) {
        return new JSONObject(map).to(clazz);
    }
}
