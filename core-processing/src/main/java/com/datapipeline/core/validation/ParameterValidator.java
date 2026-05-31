package com.datapipeline.core.validation;

import com.datapipeline.common.exception.ValidationError;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
public class ParameterValidator {

    public void validate(Map<String, Object> params) {
        if (params == null) {
            throw new ValidationError("Parameters cannot be null");
        }
        validateRequiredFields(params);
        validateFieldTypes(params);
    }

    private void validateRequiredFields(Map<String, Object> params) {
        ValidationError error = new ValidationError("Validation failed");
        boolean hasErrors = false;

        if (!params.containsKey("payload")) {
            error.addError("payload", "payload field is required");
            hasErrors = true;
        }

        if (hasErrors) {
            log.warn("Parameter validation failed: {}", error.getFieldErrors());
            throw error;
        }
    }

    private void validateFieldTypes(Map<String, Object> params) {
        Object payload = params.get("payload");
        if (payload != null && !(payload instanceof Map)) {
            throw new ValidationError("Validation failed")
                    .addError("payload", "payload must be an object");
        }
    }

    public void validateConfig(Map<String, Object> parameters) {
        if (parameters == null) {
            return;
        }

        ValidationError error = new ValidationError("Config validation failed");
        boolean hasErrors = false;

        if (parameters.containsKey("timeout")) {
            Object timeout = parameters.get("timeout");
            if (!(timeout instanceof Number)) {
                error.addError("timeout", "timeout must be a number");
                hasErrors = true;
            } else if (((Number) timeout).intValue() < 0) {
                error.addError("timeout", "timeout must be non-negative");
                hasErrors = true;
            }
        }

        if (parameters.containsKey("retries")) {
            Object retries = parameters.get("retries");
            if (!(retries instanceof Number)) {
                error.addError("retries", "retries must be a number");
                hasErrors = true;
            } else if (((Number) retries).intValue() < 0) {
                error.addError("retries", "retries must be non-negative");
                hasErrors = true;
            }
        }

        if (hasErrors) {
            log.warn("Config validation failed: {}", error.getFieldErrors());
            throw error;
        }
    }

}
