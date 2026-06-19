package com.enterprise.gateway.common.exception;

import lombok.Getter;

@Getter
public class GatewayException extends RuntimeException {

    private final Integer code;

    public GatewayException(Integer code, String message) {
        super(message);
        this.code = code;
    }
}
