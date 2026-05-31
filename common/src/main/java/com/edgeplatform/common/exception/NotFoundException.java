package com.edgeplatform.common.exception;

import java.io.Serial;

public class NotFoundException extends BusinessException {

    @Serial
    private static final long serialVersionUID = 1L;

    public NotFoundException(String message) {
        super(404, message);
    }

    public NotFoundException(String resourceType, String id) {
        super(404, String.format("%s not found with id: %s", resourceType, id));
    }
}
