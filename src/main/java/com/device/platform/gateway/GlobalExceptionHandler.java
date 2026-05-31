package com.device.platform.gateway;

import com.device.platform.common.ApiResponse;
import com.device.platform.common.BusinessException;
import com.device.platform.common.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleBusinessException(BusinessException e, ServerWebExchange exchange) {
        String traceId = getTraceId(exchange, e.getTraceId());
        log.warn("业务异常: code={}, message={}, traceId={}", e.getCode(), e.getMessage(), traceId);

        ApiResponse<Void> response = ApiResponse.error(e.getCode(), e.getMessage(), traceId);
        HttpStatus status = HttpStatus.resolve(e.getCode());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        return Mono.just(ResponseEntity.status(status).body(response));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<ApiResponse<Map<String, String>>>> handleValidationException(
            WebExchangeBindException e, ServerWebExchange exchange) {
        String traceId = getTraceId(exchange, null);
        Map<String, String> errors = new HashMap<>();

        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            errors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }

        log.warn("参数验证失败: traceId={}, errors={}", traceId, errors);

        ApiResponse<Map<String, String>> response = new ApiResponse<>();
        response.setCode(400);
        response.setMessage("参数验证失败");
        response.setData(errors);
        response.setTraceId(traceId);

        return Mono.just(ResponseEntity.badRequest().body(response));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleIllegalArgumentException(
            IllegalArgumentException e, ServerWebExchange exchange) {
        String traceId = getTraceId(exchange, null);
        log.warn("参数非法: message={}, traceId={}", e.getMessage(), traceId);

        ApiResponse<Void> response = ApiResponse.error(400, e.getMessage(), traceId);
        return Mono.just(ResponseEntity.badRequest().body(response));
    }

    @ExceptionHandler(TimeoutException.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleTimeoutException(
            TimeoutException e, ServerWebExchange exchange) {
        String traceId = getTraceId(exchange, null);
        log.error("请求超时: traceId={}", traceId, e);

        ApiResponse<Void> response = ApiResponse.error(504, "上游服务响应超时", traceId);
        return Mono.just(ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(response));
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleGenericException(Exception e, ServerWebExchange exchange) {
        String traceId = getTraceId(exchange, null);
        log.error("系统异常: traceId={}", traceId, e);

        ApiResponse<Void> response = ApiResponse.error(500, "内部处理错误", traceId);
        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response));
    }

    private String getTraceId(ServerWebExchange exchange, String fallbackTraceId) {
        if (fallbackTraceId != null && !fallbackTraceId.isEmpty()) {
            return fallbackTraceId;
        }
        Object traceIdObj = exchange.getAttribute("traceId");
        if (traceIdObj != null) {
            return traceIdObj.toString();
        }
        TraceContext ctx = exchange.getAttribute("traceContext");
        if (ctx != null) {
            return ctx.getTraceId();
        }
        return null;
    }

    public static class TimeoutException extends RuntimeException {
        public TimeoutException(String message) {
            super(message);
        }
    }
}
