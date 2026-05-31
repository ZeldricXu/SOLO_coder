package com.monitoring.common.constants;

public class CommonConstants {

    private CommonConstants() {
    }

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    public static final String DEFAULT_NAMESPACE = "default";

    public static final String STATUS_RUNNING = "running";

    public static final String STATUS_PENDING = "pending";

    public static final String STATUS_COMPLETED = "completed";

    public static final String STATUS_FAILED = "failed";

    public static final String STATUS_PROVISIONING = "provisioning";

    public static final String PHASE_INIT = "init";

    public static final String PHASE_VALIDATING = "validating";

    public static final String PHASE_EXECUTING = "executing";

    public static final String PHASE_COMPLETED = "completed";

    public static final Integer CODE_SUCCESS = 200;

    public static final Integer CODE_CREATED = 201;

    public static final Integer CODE_BAD_REQUEST = 400;

    public static final Integer CODE_UNAUTHORIZED = 401;

    public static final Integer CODE_FORBIDDEN = 403;

    public static final Integer CODE_NOT_FOUND = 404;

    public static final Integer CODE_VALIDATION_ERROR = 422;

    public static final Integer CODE_TOO_MANY_REQUESTS = 429;

    public static final Integer CODE_INTERNAL_ERROR = 500;

    public static final Integer CODE_TIMEOUT = 504;
}
