package com.exam.common;

import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

@Getter
public class BusinessException extends RuntimeException {
    private final Integer code;
    private final String message;
    private final Map<String, Object> data;

    public BusinessException(String message) {
        super(message);
        this.code = ResultCode.ERROR.getCode();
        this.message = message;
        this.data = new HashMap<>();
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
        this.message = message;
        this.data = new HashMap<>();
    }

    public BusinessException(String codeStr, String message, Map<String, ?> dataMap) {
        super(message);
        int parsed;
        try {
            parsed = Integer.parseInt(codeStr);
        } catch (NumberFormatException e) {
            parsed = 1023;
        }
        this.code = parsed;
        this.message = message;
        this.data = new HashMap<>(dataMap != null ? dataMap : new HashMap<>());
    }

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
        this.message = resultCode.getMessage();
        this.data = new HashMap<>();
    }
}
