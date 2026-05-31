package com.nftindexer.config;

import com.nftindexer.common.OptimisticRetry;
import com.nftindexer.exception.OptimisticLockException;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OptimisticRetryAspect {

    @Around("@annotation(retry)")
    public Object retryOptimisticLock(ProceedingJoinPoint pjp, OptimisticRetry retry) throws Throwable {
        int maxAttempts = retry.maxAttempts();
        long backoffMs = retry.backoffMs();
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return pjp.proceed();
            } catch (OptimisticLockException | DuplicateKeyException e) {
                lastException = e;
                log.warn("Optimistic lock conflict on attempt {}/{} for method {}",
                        attempt, maxAttempts, pjp.getSignature().getName());
                if (attempt < maxAttempts) {
                    Thread.sleep(backoffMs * attempt);
                }
            }
        }

        log.error("Optimistic lock failed after {} attempts for method {}",
                maxAttempts, pjp.getSignature().getName());
        throw lastException != null ? lastException :
                new OptimisticLockException("操作失败，请重试");
    }
}
