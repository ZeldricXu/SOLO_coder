package com.parking.platform.common.constant;

public final class Constants {

    private Constants() {}

    public static final String HEADER_REQUEST_ID = "X-Request-ID";
    public static final String HEADER_TRACE_ID = "X-Trace-ID";
    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String HEADER_USER_ID = "X-User-ID";
    public static final String HEADER_USER_ROLES = "X-User-Roles";
    public static final String HEADER_CONTENT_TYPE = "Content-Type";

    public static final String AUTH_BEARER = "Bearer ";
    public static final String AUTH_BASIC = "Basic ";

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_USER = "USER";
    public static final String ROLE_GUEST = "GUEST";
    public static final String ROLE_SERVICE = "SERVICE";

    public static final String DEFAULT_NAMESPACE = "default";
    public static final String PRODUCTION_NAMESPACE = "production";
    public static final String STAGING_NAMESPACE = "staging";
    public static final String DEVELOPMENT_NAMESPACE = "development";

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELLED = "cancelled";
    public static final String STATUS_TIMEOUT = "timeout";

    public static final String PHASE_INITIALIZING = "initializing";
    public static final String PHASE_QUEUED = "queued";
    public static final String PHASE_RUNNING = "running";
    public static final String PHASE_PROCESSING = "processing";
    public static final String PHASE_FINALIZING = "finalizing";
    public static final String PHASE_COMPLETED = "completed";
    public static final String PHASE_FAILED = "failed";

    public static final int DEFAULT_PAGE = 1;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 1000;

    public static final int DEFAULT_TIMEOUT = 30;
    public static final int DEFAULT_RETRIES = 3;
    public static final int DEFAULT_BACKOFF = 1000;

    public static final long DEFAULT_RATE_LIMIT_PER_MINUTE = 100;
    public static final long DEFAULT_RATE_LIMIT_PER_HOUR = 1000;

    public static final String EVENT_TASK_CREATED = "task.created";
    public static final String EVENT_TASK_STARTED = "task.started";
    public static final String EVENT_TASK_COMPLETED = "task.completed";
    public static final String EVENT_TASK_FAILED = "task.failed";
    public static final String EVENT_NOTIFICATION_SENT = "notification.sent";
    public static final String EVENT_NOTIFICATION_FAILED = "notification.failed";

    public static final String RESOURCE_TYPE_TASK = "task";
    public static final String RESOURCE_TYPE_JOB = "job";
    public static final String RESOURCE_TYPE_ENVIRONMENT = "environment";
    public static final String RESOURCE_TYPE_DOCUMENT = "document";
}
