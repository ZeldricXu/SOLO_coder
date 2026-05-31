package com.edgeplatform.common.exception;

import java.io.Serial;

public class TimeoutException extends BusinessException {

    @Serial
    private static final long serialVersionUID = 1L;

    public TimeoutException(String message) {
        super(504, message);
    }
}
