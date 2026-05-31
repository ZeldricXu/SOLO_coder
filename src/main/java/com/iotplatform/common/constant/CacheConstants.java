package com.iotplatform.common.constant;

public final class CacheConstants {

    public static final String CONFIG_CACHE_PREFIX = "config:";
    public static final String ROUTE_CACHE_PREFIX = "route:";
    public static final String RATE_LIMIT_PREFIX = "rate_limit:";
    public static final String DEVICE_CACHE_PREFIX = "device:";

    public static final long CONFIG_CACHE_SECONDS = 600;
    public static final long ROUTE_CACHE_SECONDS = 300;
    public static final long RATE_LIMIT_SECONDS = 60;
    public static final long DEVICE_CACHE_SECONDS = 3600;

    public static final int CONFIG_CACHE_MAX_SIZE = 10000;
    public static final int ROUTE_CACHE_MAX_SIZE = 1000;
    public static final int RATE_LIMIT_CACHE_MAX_SIZE = 10000;
    public static final int DEVICE_CACHE_MAX_SIZE = 50000;

    public static final String KEY_SEPARATOR = ":";

    private CacheConstants() {
    }
}
