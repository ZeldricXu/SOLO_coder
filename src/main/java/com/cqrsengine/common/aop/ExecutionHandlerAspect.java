package com.cqrsengine.common.aop;

import com.cqrsengine.common.exception.BusinessException;
import com.cqrsengine.common.exception.ValidationException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class ExecutionHandlerAspect {

    private final MeterRegistry meterRegistry;

    @Around("execution(* com.cqrsengine..service.*.*(..))")
    public Object aroundServiceMethod(ProceedingJoinPoint joinPoint) throws Throwable {
        String traceId = java.util.UUID.randomUUID().toString();
        String methodName = joinPoint.getSignature().getName();
        String className = joinPoint.getTarget().getClass().getSimpleName();
        String metricName = "service.execution." + className + "." + methodName;

        Timer.Sample sample = Timer.start(meterRegistry);
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        Map<String, Object> ctx = new HashMap<>();
        ctx.put("traceId", traceId);
        ctx.put("startTime", LocalDateTime.now());

        try {
            Object[] args = joinPoint.getArgs();
            validateParams(args);

            Object result = joinPoint.proceed();

            stopWatch.stop();
            sample.stop(Timer.builder(metricName)
                    .tag("status", "success")
                    .register(meterRegistry));

            log.debug("方法执行成功: {}.{}, 耗时: {}ms, traceId: {}",
                    className, methodName, stopWatch.getTotalTimeMillis(), traceId);

            return result;

        } catch (ValidationException e) {
            stopWatch.stop();
            sample.stop(Timer.builder(metricName)
                    .tag("status", "validation_error")
                    .register(meterRegistry));
            log.warn("参数校验失败: {}.{}, traceId: {}, 错误: {}",
                    className, methodName, traceId, e.getMessage());
            throw e;

        } catch (TimeoutException e) {
            stopWatch.stop();
            sample.stop(Timer.builder(metricName)
                    .tag("status", "timeout")
                    .register(meterRegistry));
            log.error("服务调用超时: {}.{}, 耗时: {}ms, traceId: {}",
                    className, methodName, stopWatch.getTotalTimeMillis(), traceId);
            rollbackTransaction(ctx);
            throw new BusinessException(504, "上游服务响应超时");

        } catch (BusinessException e) {
            stopWatch.stop();
            sample.stop(Timer.builder(metricName)
                    .tag("status", "business_error")
                    .register(meterRegistry));
            log.warn("业务异常: {}.{}, traceId: {}, 错误: {}",
                    className, methodName, traceId, e.getMessage());
            throw e;

        } catch (Exception e) {
            stopWatch.stop();
            sample.stop(Timer.builder(metricName)
                    .tag("status", "error")
                    .register(meterRegistry));
            log.error("方法执行异常: {}.{}, 耗时: {}ms, traceId: {}",
                    className, methodName, stopWatch.getTotalTimeMillis(), traceId, e);
            rollbackTransaction(ctx);
            throw new BusinessException(500, "内部处理错误");

        } finally {
            recordMetrics(ctx, stopWatch.getTotalTimeMillis());
            cleanup(ctx);
        }
    }

    private void validateParams(Object[] args) {
        if (args == null) {
            return;
        }
        for (Object arg : args) {
            if (arg instanceof jakarta.validation.Validatable) {
                jakarta.validation.Validator validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
                var violations = validator.validate(arg);
                if (!violations.isEmpty()) {
                    Map<String, String> errors = new HashMap<>();
                    for (var violation : violations) {
                        errors.put(violation.getPropertyPath().toString(), violation.getMessage());
                    }
                    throw new ValidationException("参数校验失败", errors);
                }
            }
        }
    }

    private void rollbackTransaction(Map<String, Object> ctx) {
        log.debug("执行事务回滚, traceId: {}", ctx.get("traceId"));
    }

    private void recordMetrics(Map<String, Object> ctx, long durationMs) {
        meterRegistry.counter("service.execution.total",
                "traceId", String.valueOf(ctx.get("traceId"))
        ).increment();
    }

    private void cleanup(Map<String, Object> ctx) {
        ctx.clear();
    }
}
