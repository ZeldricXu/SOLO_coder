package com.solocoder.dns.common.util;

import cn.hutool.json.JSONUtil;

public class JsonUtils {
    public static String toJson(Object obj) {
        return JSONUtil.toJsonStr(obj);
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        return JSONUtil.toBean(json, clazz);
    }
}
