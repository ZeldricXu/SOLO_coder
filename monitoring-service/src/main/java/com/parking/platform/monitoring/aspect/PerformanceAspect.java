package com.parking.platform.monitoring.aspect;

import com.parking.platform.monitoring.service.MonitoringService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class PerformanceAspect {

    private final MonitoringService monitoringService;

    public PerformanceAspect(MonitoringService monitoringService) {
        this.monitoringService = monitoringService;
    }

    @Around("execution(* com.parking.platform.monitoring.controller..*(..))")
    public Object measurePerformance(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String methodName = signature.getDeclaringType().getSimpleName() + "." + signature.getName();
        boolean success = true;

        try {
            return joinPoint.proceed();
        } catch (Throwable e) {
            success = false;
            throw e;
        } finally {
            long duration = System.currentTimeMillis() - start;
            monitoringService.recordPerformanceMetric(methodName, duration, success);
        }
    }
}
