package com.edgescheduler.common.exception;

public class OptimisticLockException extends RuntimeException {

    public OptimisticLockException(String message) {
        super(message);
    }

    public OptimisticLockException(String message, Throwable cause) {
        super(message, cause);
    }
}
