package com.edgeplatform.common.exception;

import com.edgeplatform.common.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import reactor.core.publisher.Mono;

@Slf4j
@RestControllerAdvice
@Order(-1)
public class GlobalExceptionHandler {

    @ExceptionHandler(ValidationException.class)
    public Mono<ResponseEntity<ApiResponse<Object>>> handleValidationException(ValidationException e) {
        log.warn("Validation error: {}", e.getMessage());
        return Mono.just(ResponseEntity.status(422)
                .body(ApiResponse.error(422, e.getMessage())));
    }

    @ExceptionHandler(NotFoundException.class)
    public Mono<ResponseEntity<ApiResponse<Object>>> handleNotFoundException(NotFoundException e) {
        log.warn("Resource not found: {}", e.getMessage());
        return Mono.just(ResponseEntity.status(404)
                .body(ApiResponse.error(404, e.getMessage())));
    }

    @ExceptionHandler(TimeoutException.class)
    public Mono<ResponseEntity<ApiResponse<Object>>> handleTimeoutException(TimeoutException e) {
        log.error("Operation timeout: {}", e.getMessage());
        return Mono.just(ResponseEntity.status(504)
                .body(ApiResponse.error(504, e.getMessage())));
    }

    @ExceptionHandler(BusinessException.class)
    public Mono<ResponseEntity<ApiResponse<Object>>> handleBusinessException(BusinessException e) {
        log.error("Business error: {}", e.getMessage());
        return Mono.just(ResponseEntity.status(e.getCode())
                .body(ApiResponse.error(e.getCode(), e.getMessage())));
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<ApiResponse<Object>>> handleGenericException(Exception e) {
        log.error("Internal server error", e);
        return Mono.just(ResponseEntity.status(500)
                .body(ApiResponse.error(500, "内部处理错误")));
    }
}
