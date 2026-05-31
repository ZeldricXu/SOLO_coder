package com.edgescheduler.common.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class MetricsAspect {

    private final MeterRegistry meterRegistry;
    private final AtomicLong activeRequests = new AtomicLong(0);

    @Around("execution(* com.edgescheduler.modules..controller.*.*(..))")
    public Object measureController(ProceedingJoinPoint joinPoint) throws Throwable {
        String methodName = joinPoint.getSignature().getName();
        String className = joinPoint.getSignature().getDeclaringType().getSimpleName();
        
        activeRequests.incrementAndGet();
        meterRegistry.gauge("edge_scheduler_active_requests", activeRequests.get());

        long start = System.currentTimeMillis();
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            Object result = joinPoint.proceed();
            
            if (result instanceof Mono<?> mono) {
                return mono.doOnSuccess(r -> recordSuccess(className, methodName, start, sample))
                        .doOnError(e -> recordError(className, methodName, start, sample));
            }
            
            recordSuccess(className, methodName, start, sample);
            return result;
            
        } catch (Throwable e) {
            recordError(className, methodName, start, sample);
            throw e;
        } finally {
            activeRequests.decrementAndGet();
        }
    }

    private void recordSuccess(String className, String methodName, long start, Timer.Sample sample) {
        long duration = System.currentTimeMillis() - start;
        sample.stop(Timer.builder("edge_scheduler_requests")
                .tag("class", className)
                .tag("method", methodName)
                .tag("status", "success")
                .register(meterRegistry));
        
        meterRegistry.counter("edge_scheduler_requests_total",
                "class", className, "method", methodName, "status", "success").increment();
        
        log.debug("Request {}.{} completed in {}ms", className, methodName, duration);
    }

    private void recordError(String className, String methodName, long start, Timer.Sample sample) {
        long duration = System.currentTimeMillis() - start;
        sample.stop(Timer.builder("edge_scheduler_requests")
                .tag("class", className)
                .tag("method", methodName)
                .tag("status", "error")
                .register(meterRegistry));
        
        meterRegistry.counter("edge_scheduler_requests_total",
                "class", className, "method", methodName, "status", "error").increment();
        
        log.warn("Request {}.{} failed in {}ms", className, methodName, duration);
    }
}
