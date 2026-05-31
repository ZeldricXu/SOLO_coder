package com.didauth.common.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final Integer code;
    private final String message;
    private final String details;

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
        this.message = message;
        this.details = null;
    }

    public BusinessException(Integer code, String message, String details) {
        super(message);
        this.code = code;
        this.message = message;
        this.details = details;
    }

    public static BusinessException paramError(String message) {
        return new BusinessException(400, "参数校验失败", message);
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(404, "资源不存在", message);
    }

    public static BusinessException internalError(String message) {
        return new BusinessException(500, "内部处理错误", message);
    }
}
