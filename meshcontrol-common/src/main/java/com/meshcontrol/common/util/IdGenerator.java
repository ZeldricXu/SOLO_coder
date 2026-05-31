package com.meshcontrol.common.util;

import cn.hutool.core.util.IdUtil;

public class IdGenerator {

    private IdGenerator() {
    }

    public static String generateId() {
        return IdUtil.getSnowflakeNextIdStr();
    }

    public static String generateId(String prefix) {
        return prefix + "_" + IdUtil.getSnowflakeNextIdStr();
    }

    public static long generateLongId() {
        return IdUtil.getSnowflakeNextId();
    }

    public static String generateUuid() {
        return IdUtil.fastSimpleUUID();
    }
}
