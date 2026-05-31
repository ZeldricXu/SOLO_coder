package com.solocoder.dns.common.exception;

public class ResourceNotFoundException extends BusinessException {
    public ResourceNotFoundException(String resource, String id) {
        super(404, "资源不存在", resource + " with id " + id + " not found");
    }
}
