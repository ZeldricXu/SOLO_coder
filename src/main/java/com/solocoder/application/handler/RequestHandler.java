package com.solocoder.application.handler;

import com.solocoder.domain.model.ApiResponse;
import com.solocoder.domain.port.StructuredLoggerPort;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Component
@RequiredArgsConstructor
public class RequestHandler {

    private final StructuredLoggerPort logger;
    private final MeterRegistry meterRegistry;

    public <T, R> Mono<ApiResponse<R>> executeHandler(
            T request,
            String traceId,
            Function<HandlerContext<T>, Mono<R>> handler,
            Class<R> resultType) {

        String actualTraceId = traceId != null ? traceId : UUID.randomUUID().toString();
        Instant startTime = Instant.now();

        HandlerContext<T> ctx = new HandlerContext<>(actualTraceId, request, new HashMap<>());

        return Mono.just(ctx)
                .flatMap(context -> {
                    logger.info("开始处理请求", Map.of(
                            "traceId", actualTraceId,
                            "requestType", request != null ? request.getClass().getSimpleName() : "unknown"
                    ));

                    try {
                        validateParams(context.getRequest());
                    } catch (ValidationException e) {
                        logger.error("参数校验失败", e, Map.of("traceId", actualTraceId));
                        return Mono.just(ApiResponse.<R>error(422, e.getMessage()));
                    }

                    return handler.apply(context)
                            .flatMap(result -> {
                                persistResult(result);
                                emitEvent("task.completed", buildEvent(result));
                                recordMetrics(ctx, startTime, "success");
                                logger.info("请求处理成功", Map.of(
                                        "traceId", actualTraceId,
                                        "duration", Duration.between(startTime, Instant.now()).toMillis()
                                ));
                                return Mono.just(ApiResponse.success(result));
                            })
                            .onErrorResume(TimeoutException.class, e -> {
                                logger.error("上游服务响应超时", e, Map.of("traceId", actualTraceId));
                                recordMetrics(ctx, startTime, "timeout");
                                return Mono.just(ApiResponse.<R>error(504, "上游服务响应超时"));
                            })
                            .onErrorResume(ValidationException.class, e -> {
                                logger.error("参数校验失败", e, Map.of("traceId", actualTraceId));
                                recordMetrics(ctx, startTime, "validation_error");
                                return Mono.just(ApiResponse.<R>error(422, e.getMessage()));
                            })
                            .onErrorResume(Exception.class, e -> {
                                rollbackTransaction(ctx);
                                logger.error("内部处理错误", e, Map.of("traceId", actualTraceId));
                                recordMetrics(ctx, startTime, "error");
                                return Mono.just(ApiResponse.<R>error(500, "内部处理错误"));
                            });
                })
                .contextWrite(Context.of("traceId", actualTraceId));
    }

    private void validateParams(Object params) {
        if (params == null) {
            throw new ValidationException("请求参数不能为空");
        }
        if (params instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> paramMap = (Map<String, Object>) params;
            if (paramMap.containsKey("validate")) {
                Object validate = paramMap.get("validate");
                if (Boolean.FALSE.equals(validate)) {
                    throw new ValidationException("参数校验失败: " + paramMap.get("errorDetail"));
                }
            }
        }
    }

    private <R> void persistResult(R result) {
        logger.debug("持久化处理结果", Map.of("result", result != null ? result.getClass().getSimpleName() : "null"));
    }

    private void emitEvent(String eventName, Map<String, Object> eventData) {
        logger.debug("发送事件", Map.of("eventName", eventName, "eventData", eventData));
    }

    private <R> Map<String, Object> buildEvent(R result) {
        Map<String, Object> event = new HashMap<>();
        event.put("timestamp", Instant.now().toString());
        event.put("result", result);
        return event;
    }

    private void rollbackTransaction(HandlerContext<?> ctx) {
        logger.warn("事务回滚", Map.of("traceId", ctx.getTraceId()));
    }

    private void recordMetrics(HandlerContext<?> ctx, Instant startTime, String status) {
        Duration duration = Duration.between(startTime, Instant.now());
        Timer.builder("request.duration")
                .tag("status", status)
                .tag("traceId", ctx.getTraceId())
                .register(meterRegistry)
                .record(duration);

        ctx.setAttribute("durationMs", duration.toMillis());
        ctx.setAttribute("status", status);
    }

    public static class HandlerContext<T> {
        private final String traceId;
        private final T request;
        private final Map<String, Object> attributes;

        public HandlerContext(String traceId, T request, Map<String, Object> attributes) {
            this.traceId = traceId;
            this.request = request;
            this.attributes = attributes;
        }

        public String getTraceId() {
            return traceId;
        }

        public T getRequest() {
            return request;
        }

        public Object getAttribute(String key) {
            return attributes.get(key);
        }

        public void setAttribute(String key, Object value) {
            attributes.put(key, value);
        }

        public Map<String, Object> getAttributes() {
            return attributes;
        }
    }

    public static class ValidationException extends RuntimeException {
        private final String details;

        public ValidationException(String details) {
            super(details);
            this.details = details;
        }

        public String getDetails() {
            return details;
        }
    }

    public static class TimeoutException extends RuntimeException {
        public TimeoutException(String message) {
            super(message);
        }
    }
}
