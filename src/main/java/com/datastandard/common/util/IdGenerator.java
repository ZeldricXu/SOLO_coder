package com.datastandard.common.util;

import cn.hutool.core.util.IdUtil;

public class IdGenerator {

    public static Long generateId() {
        return IdUtil.getSnowflakeNextId();
    }

    public static String generateStrId() {
        return IdUtil.getSnowflakeNextIdStr();
    }

    public static String generateUUID() {
        return IdUtil.fastSimpleUUID();
    }

    public static String generateTraceId() {
        return IdUtil.fastSimpleUUID();
    }

    public static String generateRequestId() {
        return "req_" + IdUtil.fastSimpleUUID();
    }

    public static String generateCode(String prefix) {
        return prefix + "_" + IdUtil.getSnowflakeNextIdStr();
    }
}
