package com.tracetopology.common.exception;

import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

@Getter
public class StorageException extends BaseException {

    private final String operation;
    private final String bucket;
    private final String path;
    private final String fileId;
    private final Map<String, Object> context;

    private StorageException(Builder builder) {
        super(builder.code, builder.message, builder.cause);
        this.operation = builder.operation;
        this.bucket = builder.bucket;
        this.path = builder.path;
        this.fileId = builder.fileId;
        this.context = builder.context;
    }

    public static Builder builder(String code, String message) {
        return new Builder(code, message);
    }

    public Map<String, Object> getFullContext() {
        Map<String, Object> fullContext = new HashMap<>(context);
        if (operation != null) fullContext.put("operation", operation);
        if (bucket != null) fullContext.put("bucket", bucket);
        if (path != null) fullContext.put("path", path);
        if (fileId != null) fullContext.put("fileId", fileId);
        return fullContext;
    }

    public static class Builder {
        private final String code;
        private final String message;
        private String operation;
        private String bucket;
        private String path;
        private String fileId;
        private Throwable cause;
        private final Map<String, Object> context = new HashMap<>();

        public Builder(String code, String message) {
            this.code = code;
            this.message = message;
        }

        public Builder operation(String operation) {
            this.operation = operation;
            return this;
        }

        public Builder bucket(String bucket) {
            this.bucket = bucket;
            return this;
        }

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        public Builder fileId(String fileId) {
            this.fileId = fileId;
            return this;
        }

        public Builder cause(Throwable cause) {
            this.cause = cause;
            return this;
        }

        public Builder context(String key, Object value) {
            this.context.put(key, value);
            return this;
        }

        public StorageException build() {
            return new StorageException(this);
        }
    }
}
