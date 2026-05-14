package com.datasync.common;

public class Constants {

    public static final String SYNC_MODE_REALTIME = "realtime";
    public static final String SYNC_MODE_SCHEDULED = "scheduled";
    public static final String SYNC_MODE_MANUAL = "manual";

    public static final String SYNC_STATUS_PENDING = "pending";
    public static final String SYNC_STATUS_RUNNING = "running";
    public static final String SYNC_STATUS_COMPLETED = "completed";
    public static final String SYNC_STATUS_FAILED = "failed";
    public static final String SYNC_STATUS_RETRYING = "retrying";

    public static final String CONFLICT_STRATEGY_SOURCE_PRIORITY = "source_priority";
    public static final String CONFLICT_STRATEGY_TARGET_PRIORITY = "target_priority";
    public static final String CONFLICT_STRATEGY_MERGE = "merge";
    public static final String CONFLICT_STRATEGY_MANUAL = "manual";

    public static final String CONFLICT_TYPE_VERSION = "version_conflict";
    public static final String CONFLICT_TYPE_CONTENT = "content_conflict";
    public static final String CONFLICT_TYPE_STRUCTURE = "structure_conflict";
    public static final String CONFLICT_TYPE_DATA_SOURCE = "datasource_conflict";
    public static final String CONFLICT_TYPE_TYPE_MISMATCH = "type_mismatch_conflict";
    public static final String CONFLICT_TYPE_MIXED = "mixed_conflict";

    public static final int CONFLICT_PRIORITY_CRITICAL = 1;
    public static final int CONFLICT_PRIORITY_HIGH = 2;
    public static final int CONFLICT_PRIORITY_MEDIUM = 3;
    public static final int CONFLICT_PRIORITY_LOW = 4;

    public static final String CONFLICT_STATUS_PENDING = "pending";
    public static final String CONFLICT_STATUS_RESOLVED = "resolved";
    public static final String CONFLICT_STATUS_MANUAL_REQUIRED = "manual_required";
    public static final String CONFLICT_STATUS_AUTO_RESOLVED = "auto_resolved";
    public static final String CONFLICT_STATUS_FAILED = "failed";

    public static final String DATA_SOURCE_TYPE_MYSQL = "mysql";
    public static final String DATA_SOURCE_TYPE_POSTGRESQL = "postgresql";
    public static final String DATA_SOURCE_TYPE_REDIS = "redis";
    public static final String DATA_SOURCE_TYPE_ORACLE = "oracle";

    public static final String DATA_SOURCE_STATUS_ACTIVE = "active";
    public static final String DATA_SOURCE_STATUS_INACTIVE = "inactive";
    public static final String DATA_SOURCE_STATUS_ERROR = "error";

    public static final String SYNC_LOG_LEVEL_INFO = "INFO";
    public static final String SYNC_LOG_LEVEL_WARN = "WARN";
    public static final String SYNC_LOG_LEVEL_ERROR = "ERROR";
    public static final String SYNC_LOG_LEVEL_DEBUG = "DEBUG";

    public static final String REDIS_KEY_PREFIX_DATASOURCE = "datasource:";
    public static final String REDIS_KEY_PREFIX_TASK = "task:";
    public static final String REDIS_KEY_PREFIX_SYNC_RECORD = "sync_record:";
    public static final String REDIS_KEY_PREFIX_CONFLICT = "conflict:";
    public static final String REDIS_KEY_PREFIX_VERSION = "version:";
    public static final String REDIS_KEY_PREFIX_LOG = "sync_log:";
    public static final String REDIS_KEY_PREFIX_STATUS = "status:";

    public static final int DEFAULT_RETRY_COUNT = 3;
    public static final int DEFAULT_RETRY_INTERVAL = 5000;
    public static final int DEFAULT_SYNC_INTERVAL = 60;

    public static final int API_CODE_SUCCESS = 200;
    public static final int API_CODE_BAD_REQUEST = 400;
    public static final int API_CODE_NOT_FOUND = 404;
    public static final int API_CODE_INTERNAL_ERROR = 500;
    public static final int API_CODE_CONFLICT = 409;

    private Constants() {
    }
}
