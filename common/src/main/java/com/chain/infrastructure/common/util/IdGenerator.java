package com.chain.infrastructure.common.util;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;

public class IdGenerator {

    public static String generateId(String prefix) {
        return prefix + "_" + IdUtil.fastSimpleUUID().substring(0, 16);
    }

    public static String generateTraceId() {
        return IdUtil.fastSimpleUUID();
    }

    public static String generateHash(String content) {
        return DigestUtil.sha256Hex(content);
    }
}
