package com.enterprise.risk.common.exception;

public class RiskException extends RuntimeException {

    private final String errorCode;
    private final Object details;

    public RiskException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.details = null;
    }

    public RiskException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = null;
    }

    public RiskException(String errorCode, String message, Object details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Object getDetails() {
        return details;
    }
}
