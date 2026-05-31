package com.chaoslab.config;

import com.chaoslab.common.OptimisticRetry;
import com.chaoslab.exception.OptimisticLockException;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Slf4j
@Aspect
@Component
public class OptimisticRetryAspect {

    @Around("@annotation(optimisticRetry)")
    public Object retryOnOptimisticLock(ProceedingJoinPoint joinPoint, OptimisticRetry optimisticRetry) throws Throwable {
        int maxAttempts = optimisticRetry.maxAttempts();
        long delayMs = optimisticRetry.delayMs();

        return retry(joinPoint, maxAttempts, delayMs, 1);
    }

    private Object retry(ProceedingJoinPoint joinPoint, int maxAttempts, long delayMs, int attempt) throws Throwable {
        try {
            Object result = joinPoint.proceed();

            if (result instanceof Mono<?> monoResult) {
                return monoResult.onErrorResume(ex -> {
                    if (isOptimisticLockException(ex) && attempt < maxAttempts) {
                        log.warn("Optimistic lock conflict on attempt {}, retrying...", attempt);
                        try {
                            Thread.sleep(delayMs * attempt);
                            return (Mono<?>) retry(joinPoint, maxAttempts, delayMs, attempt + 1);
                        } catch (Throwable e) {
                            return Mono.error(e);
                        }
                    }
                    return Mono.error(ex instanceof OptimisticLockException ? ex
                            : new OptimisticLockException("Optimistic lock conflict after " + attempt + " attempts"));
                });
            }

            return result;
        } catch (OptimisticLockingFailureException | OptimisticLockException ex) {
            if (attempt < maxAttempts) {
                log.warn("Optimistic lock conflict on attempt {}, retrying after {}ms...", attempt, delayMs * attempt);
                Thread.sleep(delayMs * attempt);
                return retry(joinPoint, maxAttempts, delayMs, attempt + 1);
            }
            throw new OptimisticLockException("Optimistic lock conflict after " + maxAttempts + " attempts");
        }
    }

    private boolean isOptimisticLockException(Throwable ex) {
        return ex instanceof OptimisticLockingFailureException
                || ex instanceof OptimisticLockException
                || (ex.getCause() != null && isOptimisticLockException(ex.getCause()));
    }
}
