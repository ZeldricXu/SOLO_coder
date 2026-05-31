package com.taskflow.common.exception;

public class TimeoutException extends BusinessException {

    public TimeoutException() {
        super(504, "上游服务响应超时");
    }

    public TimeoutException(String message) {
        super(504, message);
    }
}
