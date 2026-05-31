package com.datapipeline.api;

import com.datapipeline.common.dto.ApiResponse;
import com.datapipeline.common.exception.BusinessException;
import com.datapipeline.common.exception.ValidationError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebInputException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ValidationError.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationError(ValidationError e) {
        log.warn("Validation error: {}", e.getMessage());
        return ResponseEntity.status(422).body(ApiResponse.error(422, e.getMessage()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        log.warn("Business exception: code={}, message={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getCode()).body(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<ApiResponse<Void>> handleWebInputException(ServerWebInputException e) {
        log.warn("Invalid request: {}", e.getMessage());
        return ResponseEntity.status(400).body(ApiResponse.error(400, "Invalid request: " + e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Invalid argument: {}", e.getMessage());
        return ResponseEntity.status(400).body(ApiResponse.error(400, e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneralException(Exception e) {
        log.error("Unexpected error occurred", e);
        return ResponseEntity.status(500).body(ApiResponse.error(500, "Internal server error"));
    }

}
