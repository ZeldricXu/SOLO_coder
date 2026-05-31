package com.web3platform.storageadapter.exception;

import com.web3platform.storageadapter.model.StorageUploadResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class StorageExceptionHandler {

    @ExceptionHandler(StorageException.class)
    public ResponseEntity<Map<String, Object>> handleStorageException(StorageException ex) {
        log.warn("Storage exception: code={}, message={}", ex.getErrorCode(), ex.getMessage());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("errorCode", ex.getErrorCode());
        body.put("message", ex.getMessage());
        if (ex.getStorageType() != null) {
            body.put("storageType", ex.getStorageType());
        }
        if (ex.getCid() != null) {
            body.put("cid", ex.getCid());
        }

        HttpStatus status = determineHttpStatus(ex.getErrorCode());
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Invalid argument: {}", ex.getMessage());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("errorCode", StorageErrorCode.INVALID_REQUEST);
        body.put("message", ex.getMessage());

        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        log.warn("Invalid state: {}", ex.getMessage());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("errorCode", StorageErrorCode.INVALID_REQUEST);
        body.put("message", ex.getMessage());

        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        log.error("Unexpected storage error", ex);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("errorCode", "STORAGE_999");
        body.put("message", "Internal storage error");

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private HttpStatus determineHttpStatus(String errorCode) {
        return switch (errorCode) {
            case StorageErrorCode.SESSION_NOT_FOUND, StorageErrorCode.UNSUPPORTED_STORAGE_TYPE,
                 StorageErrorCode.INVALID_REQUEST, StorageErrorCode.INVALID_CHUNK_INDEX -> HttpStatus.BAD_REQUEST;
            case StorageErrorCode.SESSION_ALREADY_COMPLETED, StorageErrorCode.CHUNK_UPLOAD_INCOMPLETE -> HttpStatus.CONFLICT;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
