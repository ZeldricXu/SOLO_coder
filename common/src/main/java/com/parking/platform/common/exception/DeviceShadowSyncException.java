package com.parking.platform.common.exception;

public class DeviceShadowSyncException extends BusinessException {

    public DeviceShadowSyncException(String message) {
        super(500, message);
    }

    public DeviceShadowSyncException(String message, Throwable cause) {
        super(500, message, cause);
    }
}
