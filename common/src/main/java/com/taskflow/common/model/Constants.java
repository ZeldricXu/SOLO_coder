package com.taskflow.common.model;

public class Constants {

    public static final String TENANT_ID_HEADER = "X-Tenant-Id";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String AUTHORIZATION_HEADER = "Authorization";
    public static final String USER_ID_HEADER = "X-User-Id";

    public static final String DEFAULT_TENANT_ID = "default";

    public static final int MAX_RETRY_ATTEMPTS = 3;

    public static final class Status {
        public static final String ACTIVE = "active";
        public static final String INACTIVE = "inactive";
        public static final String PENDING = "pending";
        public static final String RUNNING = "running";
        public static final String COMPLETED = "completed";
        public static final String FAILED = "failed";
        public static final String CANCELLED = "cancelled";
        public static final String PAUSED = "paused";
    }

    public static final class TaskPhase {
        public static final String PENDING = "pending";
        public static final String QUEUED = "queued";
        public static final String EXECUTING = "executing";
        public static final String COMPLETED = "completed";
        public static final String FAILED = "failed";
        public static final String CANCELLED = "cancelled";
    }
}
