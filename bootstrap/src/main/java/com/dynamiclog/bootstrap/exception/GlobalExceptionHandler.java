package com.dynamiclog.bootstrap.exception;

import com.dynamiclog.common.dto.ApiResponse;
import com.dynamiclog.common.exception.BusinessException;
import com.dynamiclog.common.exception.ResourceNotFoundException;
import com.dynamiclog.common.exception.ValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ValidationException.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleValidationException(ValidationException e, ServerWebExchange exchange) {
        log.warn("Validation error: {}", e.getMessage());
        return Mono.just(ResponseEntity.status(422).body(ApiResponse.error(422, e.getMessage())));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleResourceNotFoundException(ResourceNotFoundException e, ServerWebExchange exchange) {
        log.warn("Resource not found: {}", e.getMessage());
        return Mono.just(ResponseEntity.status(404).body(ApiResponse.error(404, e.getMessage())));
    }

    @ExceptionHandler(BusinessException.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleBusinessException(BusinessException e, ServerWebExchange exchange) {
        log.warn("Business error: {}", e.getMessage());
        return Mono.just(ResponseEntity.status(e.getCode()).body(ApiResponse.error(e.getCode(), e.getMessage())));
    }

    @ExceptionHandler(SecurityException.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleSecurityException(SecurityException e, ServerWebExchange exchange) {
        log.warn("Security error: {}", e.getMessage());
        return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error(401, e.getMessage())));
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleGenericException(Exception e, ServerWebExchange exchange) {
        log.error("Unexpected error", e);
        return Mono.just(ResponseEntity.status(500).body(ApiResponse.error(500, "Internal server error")));
    }
}
