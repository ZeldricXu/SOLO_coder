package com.smartflow.common.utils;

import cn.hutool.core.util.IdUtil;

public class IdGenerator {

    public static Long generateId() {
        return IdUtil.getSnowflakeNextId();
    }

    public static String generateStrId() {
        return IdUtil.getSnowflakeNextIdStr();
    }

    public static String generateTraceId() {
        return IdUtil.fastSimpleUUID();
    }
}
