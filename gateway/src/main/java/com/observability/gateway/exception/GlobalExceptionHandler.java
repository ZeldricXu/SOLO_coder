package com.observability.gateway.exception;

import com.observability.common.context.RequestContextHolder;
import com.observability.common.dto.ApiResponse;
import com.observability.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import reactor.core.publisher.Mono;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleBusinessException(BusinessException e) {
        return RequestContextHolder.get()
                .map(context -> {
                    log.warn("Business exception - traceId: {}, code: {}, message: {}",
                            context.getTraceId(), e.getCode(), e.getMessage());
                    ApiResponse<Void> response = ApiResponse.error(e.getCode(), e.getMessage());
                    response.setTraceId(context.getTraceId());
                    return ResponseEntity.status(e.getCode()).body(response);
                });
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleValidationException(WebExchangeBindException e) {
        return RequestContextHolder.get()
                .map(context -> {
                    String message = e.getBindingResult().getFieldErrors().stream()
                            .findFirst()
                            .map(error -> error.getField() + ": " + error.getDefaultMessage())
                            .orElse("Validation failed");
                    log.warn("Validation exception - traceId: {}, message: {}", context.getTraceId(), message);
                    ApiResponse<Void> response = ApiResponse.error(400, message);
                    response.setTraceId(context.getTraceId());
                    return ResponseEntity.badRequest().body(response);
                });
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleGenericException(Exception e) {
        return RequestContextHolder.get()
                .map(context -> {
                    log.error("Unexpected exception - traceId: {}", context.getTraceId(), e);
                    ApiResponse<Void> response = ApiResponse.error(500, "Internal server error");
                    response.setTraceId(context.getTraceId());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
                });
    }
}
