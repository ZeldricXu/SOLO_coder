package com.nftindexer.exception;

import com.nftindexer.common.ApiResponse;
import com.nftindexer.common.TraceContext;
import lombok.extern.slf4j.Slf4j;
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
        return TraceContext.getTraceId()
                .doOnNext(traceId -> log.warn("Business exception: code={}, message={}, traceId={}",
                        e.getCode(), e.getMessage(), traceId))
                .map(traceId -> ResponseEntity.status(e.getCode())
                        .body(ApiResponse.error(e.getCode(), e.getMessage(), traceId)));
    }

    @ExceptionHandler(OptimisticLockException.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleOptimisticLockException(OptimisticLockException e) {
        return TraceContext.getTraceId()
                .doOnNext(traceId -> log.warn("Optimistic lock exception: message={}, traceId={}",
                        e.getMessage(), traceId))
                .map(traceId -> ResponseEntity.status(409)
                        .body(ApiResponse.error(409, "并发操作冲突，请重试", traceId)));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleValidationException(WebExchangeBindException e) {
        return TraceContext.getTraceId()
                .doOnNext(traceId -> log.warn("Validation exception: message={}, traceId={}",
                        e.getMessage(), traceId))
                .map(traceId -> {
                    String message = e.getFieldErrors().stream()
                            .map(error -> error.getField() + ": " + error.getDefaultMessage())
                            .findFirst()
                            .orElse("参数验证失败");
                    return ResponseEntity.status(422)
                            .body(ApiResponse.error(422, message, traceId));
                });
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleGenericException(Exception e) {
        return TraceContext.getTraceId()
                .doOnNext(traceId -> log.error("Unexpected exception: traceId={}", traceId, e))
                .map(traceId -> ResponseEntity.status(500)
                        .body(ApiResponse.error(500, "内部服务错误", traceId)));
    }
}
