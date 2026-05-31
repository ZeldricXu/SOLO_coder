package com.datastandard.common.exception;

import com.datastandard.common.dto.ApiResponse;
import com.datastandard.common.util.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleBusinessException(BusinessException e) {
        log.error("Business exception: code={}, message={}", e.getCode(), e.getMessage(), e);
        return ApiResponse.<Void>builder()
                .code(e.getCode())
                .message(e.getMessage())
                .traceId(TraceContext.getTraceId())
                .success(false)
                .build();
    }

    @ExceptionHandler(ValidationException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleValidationException(ValidationException e) {
        log.error("Validation exception: code={}, message={}", e.getCode(), e.getMessage(), e);
        return ApiResponse.<Void>builder()
                .code(e.getCode())
                .message(e.getMessage())
                .traceId(TraceContext.getTraceId())
                .success(false)
                .errors(e.getErrors())
                .build();
    }

    @ExceptionHandler(TimeoutException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleTimeoutException(TimeoutException e) {
        log.error("Timeout exception: code={}, message={}", e.getCode(), e.getMessage(), e);
        return ApiResponse.<Void>builder()
                .code(e.getCode())
                .message(e.getMessage())
                .traceId(TraceContext.getTraceId())
                .success(false)
                .build();
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        List<String> errors = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.toList());
        log.error("Parameter validation exception: {}", errors, e);
        return ApiResponse.<Void>builder()
                .code(400)
                .message("参数校验失败")
                .traceId(TraceContext.getTraceId())
                .success(false)
                .errors(errors)
                .build();
    }

    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleBindException(BindException e) {
        List<String> errors = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.toList());
        log.error("Bind exception: {}", errors, e);
        return ApiResponse.<Void>builder()
                .code(400)
                .message("参数绑定失败")
                .traceId(TraceContext.getTraceId())
                .success(false)
                .errors(errors)
                .build();
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleException(Exception e) {
        log.error("System exception: {}", e.getMessage(), e);
        return ApiResponse.<Void>builder()
                .code(500)
                .message("系统异常，请稍后重试")
                .traceId(TraceContext.getTraceId())
                .success(false)
                .build();
    }
}
