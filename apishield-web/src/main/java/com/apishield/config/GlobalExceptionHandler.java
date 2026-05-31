package com.apishield.config;

import com.apishield.common.dto.Result;
import com.apishield.common.exception.ApiShieldException;
import com.apishield.common.exception.BusinessException;
import com.apishield.common.exception.NotFoundException;
import com.apishield.common.exception.ValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import reactor.core.publisher.Mono;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ValidationException.class)
    public Mono<ResponseEntity<Result<Void>>> handleValidationException(ValidationException e) {
        log.warn("Validation exception: {}", e.getMessage());
        return Mono.just(ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Result.error("422", e.getMessage())));
    }

    @ExceptionHandler(BusinessException.class)
    public Mono<ResponseEntity<Result<Void>>> handleBusinessException(BusinessException e) {
        log.warn("Business exception: {} - {}", e.getCode(), e.getMessage());
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(e.getCode(), e.getMessage())));
    }

    @ExceptionHandler(NotFoundException.class)
    public Mono<ResponseEntity<Result<Void>>> handleNotFoundException(NotFoundException e) {
        log.warn("Not found exception: {}", e.getMessage());
        return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.error("404", e.getMessage())));
    }

    @ExceptionHandler(BindException.class)
    public Mono<ResponseEntity<Result<Void>>> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("Bind exception: {}", message);
        return Mono.just(ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Result.error("422", message)));
    }

    @ExceptionHandler(ApiShieldException.class)
    public Mono<ResponseEntity<Result<Void>>> handleApiShieldException(ApiShieldException e) {
        log.error("ApiShield exception: {} - {}", e.getCode(), e.getMessage(), e);
        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(e.getCode(), e.getMessage())));
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<Result<Void>>> handleGenericException(Exception e) {
        log.error("Unexpected exception", e);
        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error("500", "内部服务器错误")));
    }
}
