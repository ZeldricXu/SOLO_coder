package com.solocoder.presentation.exception;

import com.solocoder.domain.model.ApiResponse;
import com.solocoder.domain.port.StructuredLoggerPort;
import jakarta.validation.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final StructuredLoggerPort logger;

    @ExceptionHandler(ValidationException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Mono<ApiResponse<Void>> handleValidationException(ValidationException e) {
        logger.error("参数校验失败", e, Map.of("error", e.getMessage()));
        return Mono.just(ApiResponse.error(422, e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Mono<ApiResponse<Void>> handleIllegalArgumentException(IllegalArgumentException e) {
        logger.error("参数错误", e, Map.of("error", e.getMessage()));
        return Mono.just(ApiResponse.error(400, e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Mono<ApiResponse<Void>> handleIllegalStateException(IllegalStateException e) {
        logger.error("状态错误", e, Map.of("error", e.getMessage()));
        return Mono.just(ApiResponse.error(409, e.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Mono<ApiResponse<Void>> handleRuntimeException(RuntimeException e) {
        logger.error("运行时异常", e, Map.of("error", e.getMessage()));
        return Mono.just(ApiResponse.error(500, "内部处理错误: " + e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Mono<ApiResponse<Void>> handleException(Exception e) {
        logger.error("未处理的异常", e, Map.of("error", e.getMessage()));
        return Mono.just(ApiResponse.error(500, "服务器内部错误"));
    }
}
