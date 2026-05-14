package com.projectcollab.exception;

public class ProjectCollabException extends RuntimeException {

    private final int code;

    public ProjectCollabException(int code, String message) {
        super(message);
        this.code = code;
    }

    public ProjectCollabException(String message) {
        super(message);
        this.code = 500;
    }

    public int getCode() {
        return code;
    }
}
