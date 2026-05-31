package com.taskflow.gateway.filter;

import com.taskflow.common.exception.BusinessException;
import com.taskflow.common.model.Result;
import com.taskflow.common.utils.JsonUtils;
import com.taskflow.logging.context.LogContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebInputException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<?>> handleBusinessException(BusinessException e) {
        log.warn("Business exception: {}", e.getMessage());
        Result<?> result = Result.error(e.getCode(), e.getMessage(), LogContext.getTraceId());
        return ResponseEntity.status(e.getCode()).body(result);
    }

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<Result<?>> handleValidationException(ServerWebInputException e) {
        log.warn("Validation exception: {}", e.getMessage());
        Result<?> result = Result.error(400, "请求参数错误: " + e.getMessage(), LogContext.getTraceId());
        return ResponseEntity.badRequest().body(result);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<?>> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("Illegal argument: {}", e.getMessage());
        Result<?> result = Result.error(400, e.getMessage(), LogContext.getTraceId());
        return ResponseEntity.badRequest().body(result);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<?>> handleGenericException(Exception e) {
        log.error("Unexpected error occurred", e);
        Result<?> result = Result.error(500, "内部服务器错误", LogContext.getTraceId());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
    }
}
