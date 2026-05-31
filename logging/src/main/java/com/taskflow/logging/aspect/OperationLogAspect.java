package com.taskflow.logging.aspect;

import com.taskflow.logging.context.LogContext;
import com.taskflow.logging.model.StructuredLog;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Aspect
@Component
public class OperationLogAspect {

    @Pointcut("@within(com.taskflow.logging.aspect.OperationLog) || @annotation(com.taskflow.logging.aspect.OperationLog)")
    public void operationLogPointcut() {}

    @Around("operationLogPointcut()")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        OperationLog operationLog = method.getAnnotation(OperationLog.class);

        String module = operationLog != null ? operationLog.module() : "default";
        String operation = operationLog != null ? operationLog.value() : method.getName();

        LogContext.setModule(module);
        long startTime = System.currentTimeMillis();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("method", method.getName());
        metadata.put("class", joinPoint.getTarget().getClass().getSimpleName());

        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - startTime;

            StructuredLog structuredLog = StructuredLog.baseBuilder()
                    .level("INFO")
                    .logger(joinPoint.getTarget().getClass().getName())
                    .message(String.format("Operation [%s] completed", operation))
                    .module(module)
                    .durationMs(duration)
                    .metadata(metadata)
                    .build();

            log.info("{}", structuredLog);
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            metadata.put("exception", e.getClass().getSimpleName());

            StructuredLog structuredLog = StructuredLog.baseBuilder()
                    .level("ERROR")
                    .logger(joinPoint.getTarget().getClass().getName())
                    .message(String.format("Operation [%s] failed: %s", operation, e.getMessage()))
                    .module(module)
                    .durationMs(duration)
                    .metadata(metadata)
                    .exception(e.getClass().getName())
                    .stackTrace(getStackTrace(e))
                    .build();

            log.error("{}", structuredLog, e);
            throw e;
        } finally {
            LogContext.clear();
        }
    }

    private String getStackTrace(Exception e) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement element : e.getStackTrace()) {
            sb.append(element.toString()).append("\n");
            if (sb.length() > 2000) break;
        }
        return sb.toString();
    }
}
