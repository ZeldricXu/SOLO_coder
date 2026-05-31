package com.chain.infrastructure.application.exception;

import com.chain.infrastructure.common.dto.ApiResponse;
import com.chain.infrastructure.common.exception.BusinessException;
import com.chain.infrastructure.common.exception.ValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import reactor.core.publisher.Mono;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ValidationException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Mono<ApiResponse<Void>> handleValidationException(ValidationException e) {
        log.warn("Validation error: {}", e.getMessage());
        return Mono.just(ApiResponse.error(422, e.getMessage()));
    }

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Mono<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        log.warn("Business error: {}", e.getMessage());
        return Mono.just(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Mono<ApiResponse<Void>> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("Invalid argument: {}", e.getMessage());
        return Mono.just(ApiResponse.error(400, e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Mono<ApiResponse<Void>> handleIllegalStateException(IllegalStateException e) {
        log.warn("Invalid state: {}", e.getMessage());
        return Mono.just(ApiResponse.error(409, e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Mono<ApiResponse<Void>> handleGenericException(Exception e) {
        log.error("Unexpected error", e);
        return Mono.just(ApiResponse.error(500, "Internal server error"));
    }
}
