package com.iotplatform.common.util;

import cn.hutool.json.JSONUtil;
import java.util.List;
import java.util.Map;

public class JsonUtils {

    public static String toJson(Object obj) {
        return JSONUtil.toJsonStr(obj);
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        return JSONUtil.toBean(json, clazz);
    }

    public static <T> List<T> fromJsonList(String json, Class<T> clazz) {
        return JSONUtil.toList(json, clazz);
    }

    public static Map<String, Object> fromJsonMap(String json) {
        return JSONUtil.parseObj(json);
    }

    public static boolean isJson(String str) {
        return JSONUtil.isJson(str);
    }
}
