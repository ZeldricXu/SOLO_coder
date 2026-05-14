package com.mobilestore.exception;

public class PermissionDeniedException extends RuntimeException {
    
    private int errorCode;

    public PermissionDeniedException(String message) {
        super(message);
        this.errorCode = 403;
    }

    public PermissionDeniedException(int errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public int getErrorCode() {
        return errorCode;
    }
}
