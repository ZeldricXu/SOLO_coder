package com.chaoslab.exception;

import com.chaoslab.common.ApiResponse;
import com.chaoslab.common.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import reactor.core.publisher.Mono;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Mono<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        log.warn("Business exception: code={}, message={}", e.getCode(), e.getMessage());
        return TraceContext.getTraceId()
                .map(traceId -> ApiResponse.error(e.getCode(), e.getMessage(), traceId));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Mono<ApiResponse<Void>> handleValidationException(WebExchangeBindException e) {
        BindingResult bindingResult = e.getBindingResult();
        String message = bindingResult.getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("Validation error: {}", message);
        return TraceContext.getTraceId()
                .map(traceId -> ApiResponse.error(422, message, traceId));
    }

    @ExceptionHandler(OptimisticLockException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Mono<ApiResponse<Void>> handleOptimisticLockException(OptimisticLockException e) {
        log.warn("Optimistic lock conflict: {}", e.getMessage());
        return TraceContext.getTraceId()
                .map(traceId -> ApiResponse.error(409, "并发冲突，请重试", traceId));
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Mono<ApiResponse<Void>> handleGenericException(Exception e) {
        log.error("Internal server error", e);
        return TraceContext.getTraceId()
                .map(traceId -> ApiResponse.error(500, "内部处理错误", traceId));
    }
}
