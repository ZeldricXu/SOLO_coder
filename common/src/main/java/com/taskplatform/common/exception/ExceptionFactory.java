package com.taskplatform.common.exception;

public final class ExceptionFactory {

    private ExceptionFactory() {}

    public static BusinessException notFound(String resourceType, String identifier) {
        return new BusinessException(404, resourceType.toUpperCase() + "_NOT_FOUND",
                resourceType + " not found: " + identifier);
    }

    public static BusinessException taskNotFound(String taskId) {
        return notFound("task", taskId);
    }

    public static BusinessException sampleNotFound(String sampleId) {
        return notFound("sample", sampleId);
    }

    public static BusinessException versionNotFound(String version) {
        return notFound("version", version);
    }

    public static BusinessException experimentNotFound(String experimentId) {
        return notFound("experiment", experimentId);
    }

    public static BusinessException invalidArgument(String message) {
        return new BusinessException(400, "INVALID_ARGUMENT", message);
    }

    public static BusinessException missingParams(String params) {
        return new BusinessException(400, "MISSING_PARAMS", "Missing required parameters: " + params);
    }

    public static BusinessException executionError(String message) {
        return new BusinessException(500, "EXECUTION_ERROR", message);
    }

    public static BusinessException executionError(String message, Throwable cause) {
        return new BusinessException(500, "EXECUTION_ERROR", message, cause);
    }

    public static BusinessException invalidState(String message) {
        return new BusinessException(400, "INVALID_STATE", message);
    }

    public static TimeoutException timeout(String operation, long timeoutMs) {
        return new TimeoutException(operation, timeoutMs);
    }

    public static ValidationException validation(String field, String message) {
        return new ValidationException(field, message);
    }
}
