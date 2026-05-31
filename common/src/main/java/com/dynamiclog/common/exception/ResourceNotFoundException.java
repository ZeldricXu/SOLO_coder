package com.dynamiclog.common.exception;

public class ResourceNotFoundException extends BusinessException {
    public ResourceNotFoundException(String resource, String id) {
        super(404, "NOT_FOUND", resource + " not found: " + id);
    }
}
