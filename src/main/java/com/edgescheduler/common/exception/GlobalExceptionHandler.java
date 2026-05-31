package com.edgescheduler.common.exception;

import com.edgescheduler.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import reactor.core.publisher.Mono;

import javax.validation.ConstraintViolationException;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Mono<Result<Void>> handleBusinessException(BusinessException e) {
        log.warn("Business exception: {}", e.getMessage());
        return Mono.just(Result.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(ValidationException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Mono<Result<Void>> handleValidationException(ValidationException e) {
        log.warn("Validation exception: {}", e.getMessage());
        return Mono.just(Result.error(422, e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Mono<Result<Void>> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.warn("Parameter validation failed: {}", message);
        return Mono.just(Result.error(422, message));
    }

    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Mono<Result<Void>> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.warn("Parameter binding failed: {}", message);
        return Mono.just(Result.error(422, message));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Mono<Result<Void>> handleConstraintViolationException(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.joining(", "));
        log.warn("Constraint violation: {}", message);
        return Mono.just(Result.error(422, message));
    }

    @ExceptionHandler(TimeoutException.class)
    @ResponseStatus(HttpStatus.GATEWAY_TIMEOUT)
    public Mono<Result<Void>> handleTimeoutException(TimeoutException e) {
        log.error("Timeout exception: {}", e.getMessage());
        return Mono.just(Result.error(504, "上游服务响应超时"));
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Mono<Result<Void>> handleException(Exception e) {
        log.error("Unexpected exception", e);
        return Mono.just(Result.error(500, "内部处理错误"));
    }
}
