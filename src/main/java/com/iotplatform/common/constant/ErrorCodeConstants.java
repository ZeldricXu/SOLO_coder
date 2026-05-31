package com.iotplatform.common.constant;

public final class ErrorCodeConstants {

    public static final int SUCCESS = 200;

    public static final int BAD_REQUEST = 400;
    public static final int UNAUTHORIZED = 401;
    public static final int FORBIDDEN = 403;
    public static final int NOT_FOUND = 404;
    public static final int CONFLICT = 409;
    public static final int RATE_LIMIT_EXCEEDED = 429;

    public static final int INTERNAL_ERROR = 500;
    public static final int SERVICE_UNAVAILABLE = 503;

    public static final int CONFIG_NOT_FOUND = 1001;
    public static final int CONFIG_VERSION_NOT_FOUND = 1002;
    public static final int CONFIG_KEY_EXISTS = 1003;
    public static final int CONFIG_INVALID = 1004;

    public static final int ROUTE_NOT_FOUND = 2001;
    public static final int ROUTE_ID_EXISTS = 2002;
    public static final int ROUTE_INVALID = 2003;

    public static final int PROTOCOL_NOT_SUPPORTED = 3001;
    public static final int PROTOCOL_CONVERSION_FAILED = 3002;
    public static final int PROTOCOL_INVALID_PAYLOAD = 3003;

    public static final int DEVICE_NOT_FOUND = 4001;
    public static final int DEVICE_OFFLINE = 4002;
    public static final int DEVICE_AUTH_FAILED = 4003;

    private ErrorCodeConstants() {
    }
}
