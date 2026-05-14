package com.social.exception;

public class SocialNetworkException extends RuntimeException {
    private final int errorCode;

    public SocialNetworkException(String message) {
        super(message);
        this.errorCode = 500;
    }

    public SocialNetworkException(int errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public int getErrorCode() {
        return errorCode;
    }
}
