package com.monitoring.common.exception;

import java.util.Map;

public class ValidationException extends MonitoringException {

    private final Map<String, String> details;

    public ValidationException(String message) {
        super(422, message);
        this.details = null;
    }

    public ValidationException(String message, Map<String, String> details) {
        super(422, message);
        this.details = details;
    }

    public Map<String, String> getDetails() {
        return details;
    }
}
