package com.edgescheduler.common.exception;

import com.edgescheduler.common.dto.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusinessException(BusinessException e) {
        log.warn("Business exception: code={}, message={}", e.getCode(), e.getMessage());
        return ApiResponse.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidationException(ConstraintViolationException e) {
        log.warn("Validation exception: {}", e.getMessage());
        return ApiResponse.badRequest(e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("Illegal argument: {}", e.getMessage());
        return ApiResponse.badRequest(e.getMessage());
    }

    @ExceptionHandler(OptimisticLockException.class)
    public ApiResponse<Void> handleOptimisticLockException(OptimisticLockException e) {
        log.warn("Optimistic lock conflict: {}", e.getMessage());
        return ApiResponse.conflict(e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleGenericException(Exception e) {
        log.error("Unexpected exception", e);
        return ApiResponse.internalError("Internal processing error: " + e.getMessage());
    }
}
