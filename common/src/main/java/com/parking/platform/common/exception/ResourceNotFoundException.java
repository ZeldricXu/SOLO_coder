package com.parking.platform.common.exception;

public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(String resourceType, String id) {
        super(404, resourceType + " not found: " + id);
    }

    public ResourceNotFoundException(String message) {
        super(404, message);
    }
}
