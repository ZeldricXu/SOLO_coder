package com.dynamiclog.common.util;

import java.util.UUID;

public class IdGenerator {
    private IdGenerator() {}

    public static String generateId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static String generateId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
