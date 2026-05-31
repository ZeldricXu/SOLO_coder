package com.llmgateway.common.constant;

public interface CommonConstants {

    String TRACE_ID_HEADER = "X-Trace-Id";
    String USER_ID_HEADER = "X-User-Id";
    String TENANT_ID_HEADER = "X-Tenant-Id";

    String STATUS_ACTIVE = "active";
    String STATUS_INACTIVE = "inactive";
    String STATUS_PENDING = "pending";
    String STATUS_RUNNING = "running";
    String STATUS_SUCCESS = "success";
    String STATUS_FAILED = "failed";

    String PHASE_INITIALIZING = "initializing";
    String PHASE_PROCESSING = "processing";
    String PHASE_COMPLETED = "completed";
    String PHASE_FAILED = "failed";

    String STAGE_DEVELOPMENT = "development";
    String STAGE_STAGING = "staging";
    String STAGE_PRODUCTION = "production";
    String STAGE_ARCHIVED = "archived";

    Integer DEFAULT_PAGE_NUM = 1;
    Integer DEFAULT_PAGE_SIZE = 10;
    Integer MAX_PAGE_SIZE = 100;
}
