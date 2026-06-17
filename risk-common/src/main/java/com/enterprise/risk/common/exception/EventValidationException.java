package com.enterprise.risk.common.exception;

public class EventValidationException extends RiskException {

    public EventValidationException(String message) {
        super("EVENT_VALIDATION_ERROR", message);
    }

    public EventValidationException(String message, Object details) {
        super("EVENT_VALIDATION_ERROR", message, details);
    }
}
