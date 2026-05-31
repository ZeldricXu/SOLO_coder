package com.orchestration.common.api;

public class ApiConstants {

    public static final String API_V1_PREFIX = "/api/v1";

    public static final String HEADER_TENANT_ID = "X-Tenant-Id";
    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    public static final int CODE_SUCCESS = 200;
    public static final int CODE_CREATED = 201;
    public static final int CODE_BAD_REQUEST = 400;
    public static final int CODE_UNAUTHORIZED = 401;
    public static final int CODE_FORBIDDEN = 403;
    public static final int CODE_NOT_FOUND = 404;
    public static final int CODE_CONFLICT = 409;
    public static final int CODE_INTERNAL_ERROR = 500;
    public static final int CODE_SERVICE_UNAVAILABLE = 503;
}
