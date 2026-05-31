package com.datamasker.application.aspect;

import com.datamasker.domain.mpc.monitor.MpcExecutionTracer;
import com.datamasker.domain.mpc.monitor.MpcMetrics;
import com.datamasker.domain.mpc.model.MpcSession;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
@RequiredArgsConstructor
public class MpcMonitoringAspect {

    private final MpcMetrics mpcMetrics;
    private final MpcExecutionTracer executionTracer;

    @Around("execution(* com.datamasker.application.service.MpcService.createSession(..))")
    public Object monitorCreateSession(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();
        String spanId = executionTracer.startSpan("createSession", "");
        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - start;
            if (result instanceof MpcSession session) {
                mpcMetrics.recordSessionCreation(duration, session.getProtocolType(), session.getPartyCount());
                mpcMetrics.incrementActive();
            }
            executionTracer.endSpan(spanId, true);
            return result;
        } catch (Throwable throwable) {
            executionTracer.endSpan(spanId, false);
            throw throwable;
        }
    }

    @Around("execution(* com.datamasker.application.service.MpcService.submitInput(..))")
    public Object monitorSubmitInput(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();
        String sessionId = joinPoint.getArgs().length > 0 ? joinPoint.getArgs()[0].toString() : "";
        String spanId = executionTracer.startSpan("submitInput", sessionId);
        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - start;
            mpcMetrics.recordInputSubmission(duration, sessionId);
            executionTracer.endSpan(spanId, true);
            return result;
        } catch (Throwable throwable) {
            executionTracer.endSpan(spanId, false);
            throw throwable;
        }
    }

    @Around("execution(* com.datamasker.application.service.MpcService.executeComputation(..))")
    public Object monitorExecuteComputation(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();
        String sessionId = joinPoint.getArgs().length > 0 ? joinPoint.getArgs()[0].toString() : "";
        String spanId = executionTracer.startSpan("executeComputation", sessionId);
        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - start;
            mpcMetrics.recordComputation(duration, "", true);
            mpcMetrics.decrementActive();
            executionTracer.endSpan(spanId, true);
            return result;
        } catch (Throwable throwable) {
            mpcMetrics.recordComputation(System.currentTimeMillis() - start, "", false);
            mpcMetrics.decrementActive();
            executionTracer.endSpan(spanId, false);
            throw throwable;
        }
    }
}
