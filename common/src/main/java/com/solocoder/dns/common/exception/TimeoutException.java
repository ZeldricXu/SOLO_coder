package com.solocoder.dns.common.exception;

public class TimeoutException extends BusinessException {
    public TimeoutException(String message) {
        super(504, "上游服务响应超时", message);
    }
}
