package com.taskflow.common.utils;

import cn.hutool.core.util.IdUtil;

public class IdGenerator {

    public static String generateId() {
        return IdUtil.simpleUUID();
    }

    public static String generateId(String prefix) {
        return prefix + "_" + IdUtil.simpleUUID().substring(0, 12);
    }

    public static long generateSnowflakeId() {
        return IdUtil.getSnowflakeNextId();
    }

    public static String generateTraceId() {
        return "trace_" + IdUtil.fastSimpleUUID();
    }
}
