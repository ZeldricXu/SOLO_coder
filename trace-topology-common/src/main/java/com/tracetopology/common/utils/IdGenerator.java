package com.tracetopology.common.utils;

import cn.hutool.core.util.IdUtil;

public class IdGenerator {

    public static String generateId() {
        return IdUtil.simpleUUID();
    }

    public static String generateId(String prefix) {
        return prefix + "_" + IdUtil.simpleUUID();
    }
}
