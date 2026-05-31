package com.metricplatform.datasource;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Slf4j
@Aspect
@Component
public class DataSourceAspect implements Ordered {

    @Around("@annotation(com.metricplatform.datasource.ReadOnly) || @within(com.metricplatform.datasource.ReadOnly)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        ReadOnly readOnly = method.getAnnotation(ReadOnly.class);
        if (readOnly == null) {
            readOnly = joinPoint.getTarget().getClass().getAnnotation(ReadOnly.class);
        }

        String dataSourceType = readOnly != null ? DataSourceType.SLAVE : DataSourceType.MASTER;

        DataSourceType.setDataSourceType(dataSourceType);
        log.debug("设置数据源: {} -> {}", joinPoint.getSignature().toShortString(), dataSourceType);

        try {
            return joinPoint.proceed();
        } finally {
            DataSourceType.clearDataSourceType();
            log.debug("清除数据源: {}", joinPoint.getSignature().toShortString());
        }
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
