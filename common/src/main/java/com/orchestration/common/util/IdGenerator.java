package com.orchestration.common.util;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.RandomUtil;

public class IdGenerator {

    public static Long generateId() {
        return IdUtil.getSnowflakeNextId();
    }

    public static String generateStrId() {
        return IdUtil.getSnowflakeNextIdStr();
    }

    public static String generateUuid() {
        return IdUtil.fastSimpleUUID();
    }

    public static String generateId(String prefix) {
        return prefix + "_" + IdUtil.getSnowflakeNextIdStr();
    }

    public static String generateTraceId() {
        return "trace_" + System.currentTimeMillis() + "_" + RandomUtil.randomString(8);
    }
}
