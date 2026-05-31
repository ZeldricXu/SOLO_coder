package com.iotplatform.common.constant;

public final class MetricConstants {

    public static final String CONFIG_CREATE_SUCCESS = "config.create.success";
    public static final String CONFIG_CREATE_FAILURE = "config.create.failure";
    public static final String CONFIG_CREATE_LATENCY = "config.create.latency";
    public static final String CONFIG_UPDATE_SUCCESS = "config.update.success";
    public static final String CONFIG_UPDATE_FAILURE = "config.update.failure";
    public static final String CONFIG_UPDATE_LATENCY = "config.update.latency";
    public static final String CONFIG_ROLLBACK_SUCCESS = "config.rollback.success";
    public static final String CONFIG_ROLLBACK_FAILURE = "config.rollback.failure";
    public static final String CONFIG_ROLLBACK_LATENCY = "config.rollback.latency";
    public static final String CONFIG_CACHE_HIT = "config.cache.hit";
    public static final String CONFIG_CACHE_MISS = "config.cache.miss";

    public static final String GATEWAY_PROTOCOL_CONVERT_SUCCESS = "gateway.protocol.convert.success";
    public static final String GATEWAY_PROTOCOL_CONVERT_FAILURE = "gateway.protocol.convert.failure";
    public static final String GATEWAY_PROTOCOL_CONVERT_LATENCY = "gateway.protocol.convert.latency";
    public static final String GATEWAY_RATELIMIT_EXCEEDED = "gateway.ratelimit.exceeded";
    public static final String GATEWAY_RATELIMIT_ALLOWED = "gateway.ratelimit.allowed";
    public static final String GATEWAY_REQUEST_DURATION = "gateway.request.duration";

    public static final String TAG_IP = "ip";
    public static final String TAG_NAMESPACE = "namespace";
    public static final String TAG_PROTOCOL = "protocol";
    public static final String TAG_ROUTE_ID = "route_id";

    private MetricConstants() {
    }
}
