package com.iotplatform.common.util;

import com.iotplatform.common.constant.CacheConstants;

public final class CacheKeyUtil {

    private CacheKeyUtil() {
    }

    public static String configKey(String namespace, String configKey) {
        return buildKey(CacheConstants.CONFIG_CACHE_PREFIX, namespace, configKey);
    }

    public static String routeKey(String routeId) {
        return buildKey(CacheConstants.ROUTE_CACHE_PREFIX, routeId);
    }

    public static String rateLimitKey(String clientIp) {
        return buildKey(CacheConstants.RATE_LIMIT_PREFIX, clientIp);
    }

    public static String deviceKey(String deviceId) {
        return buildKey(CacheConstants.DEVICE_CACHE_PREFIX, deviceId);
    }

    private static String buildKey(String prefix, String... parts) {
        StringBuilder sb = new StringBuilder(prefix);
        for (String part : parts) {
            sb.append(CacheConstants.KEY_SEPARATOR).append(part);
        }
        return sb.toString();
    }
}
