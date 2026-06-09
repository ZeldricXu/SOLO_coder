package com.datateam.loganalyzer.notification;

import com.datateam.loganalyzer.model.AlertEvent;
import com.datateam.loganalyzer.model.NotificationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public abstract class AbstractNotifier implements Notifier {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    protected static final int DEFAULT_MAX_RETRIES = 3;
    protected static final long DEFAULT_INITIAL_DELAY_MS = 1000;
    protected static final double DEFAULT_BACKOFF_MULTIPLIER = 2.0;
    protected static final long DEFAULT_TIMEOUT_MS = 5000;
    protected static final int DEFAULT_CIRCUIT_BREAKER_THRESHOLD = 5;
    protected static final long DEFAULT_CIRCUIT_BREAKER_RESET_MS = 60000;

    protected final NotificationConfig config;
    protected final TemplateEngine templateEngine;

    private final int maxRetries;
    private final long initialDelayMs;
    private final double backoffMultiplier;
    private final long timeoutMs;
    private final int circuitBreakerThreshold;
    private final long circuitBreakerResetMs;

    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private volatile boolean circuitBreakerOpen = false;
    private final AtomicLong circuitBreakerOpenedAt = new AtomicLong(0);

    private final ExecutorService executorService;

    protected AbstractNotifier(NotificationConfig config) {
        this(config, new TemplateEngine());
    }

    protected AbstractNotifier(NotificationConfig config, TemplateEngine templateEngine) {
        this.config = config;
        this.templateEngine = templateEngine;

        this.maxRetries = config.getMaxRetries() > 0 ? config.getMaxRetries() : DEFAULT_MAX_RETRIES;
        this.initialDelayMs = config.getInitialDelayMs() > 0 ? config.getInitialDelayMs() : DEFAULT_INITIAL_DELAY_MS;
        this.backoffMultiplier = config.getBackoffMultiplier() > 0 ? config.getBackoffMultiplier() : DEFAULT_BACKOFF_MULTIPLIER;
        this.timeoutMs = config.getTimeoutMs() > 0 ? config.getTimeoutMs() : DEFAULT_TIMEOUT_MS;
        this.circuitBreakerThreshold = config.getCircuitBreakerThreshold() > 0 ? config.getCircuitBreakerThreshold() : DEFAULT_CIRCUIT_BREAKER_THRESHOLD;
        this.circuitBreakerResetMs = config.getCircuitBreakerResetMs() > 0 ? config.getCircuitBreakerResetMs() : DEFAULT_CIRCUIT_BREAKER_RESET_MS;

        this.executorService = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "notifier-" + getName());
            t.setDaemon(true);
            return t;
        });
    }

    protected abstract boolean doSend(AlertEvent alert) throws Exception;

    @Override
    public final boolean send(AlertEvent alert) {
        if (!isEnabled()) {
            logger.warn("Channel {} is disabled, skipping notification for alert: {}", getName(), alert.getRuleId());
            return false;
        }

        if (isCircuitBreakerOpen()) {
            long openDuration = System.currentTimeMillis() - circuitBreakerOpenedAt.get();
            if (openDuration >= circuitBreakerResetMs) {
                resetCircuitBreaker();
                logger.info("Circuit breaker for {} has been reset after {}ms", getName(), openDuration);
            } else {
                logger.warn("Circuit breaker is open for {}, skipping notification (will reset in {}ms)",
                    getName(), circuitBreakerResetMs - openDuration);
                return false;
            }
        }

        return sendWithRetry(alert);
    }

    private boolean sendWithRetry(AlertEvent alert) {
        int attempt = 0;
        long delay = initialDelayMs;
        Exception lastException = null;

        while (attempt <= maxRetries) {
            attempt++;
            try {
                boolean result = sendWithTimeout(alert);
                if (result) {
                    onSuccess();
                    return true;
                }
                lastException = new RuntimeException("Send returned false");
            } catch (TimeoutException e) {
                lastException = e;
                logger.warn("Attempt {} for {} timed out after {}ms (alert: {})",
                    attempt, getName(), timeoutMs, alert.getRuleId());
            } catch (Exception e) {
                lastException = e;
                logger.warn("Attempt {} for {} failed: {} (alert: {})",
                    attempt, getName(), e.getMessage(), alert.getRuleId());
            }

            if (attempt <= maxRetries) {
                logger.info("Retrying {} in {}ms (attempt {}/{})", getName(), delay, attempt, maxRetries);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                delay = (long) (delay * backoffMultiplier);
            }
        }

        onFailure(lastException);
        logger.error("All {} attempts failed for {} (alert: {})", maxRetries + 1, getName(), alert.getRuleId());
        return false;
    }

    private boolean sendWithTimeout(AlertEvent alert) throws Exception {
        Future<Boolean> future = executorService.submit(() -> doSend(alert));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new RuntimeException(cause);
        }
    }

    protected void onSuccess() {
        failureCount.set(0);
        lastFailureTime.set(0);
    }

    protected void onFailure(Exception e) {
        int failures = failureCount.incrementAndGet();
        lastFailureTime.set(System.currentTimeMillis());

        if (failures >= circuitBreakerThreshold && !circuitBreakerOpen) {
            openCircuitBreaker();
        }
    }

    protected void openCircuitBreaker() {
        circuitBreakerOpen = true;
        circuitBreakerOpenedAt.set(System.currentTimeMillis());
        logger.error("Circuit breaker opened for {} after {} consecutive failures", getName(), failureCount.get());
    }

    protected void resetCircuitBreaker() {
        circuitBreakerOpen = false;
        circuitBreakerOpenedAt.set(0);
        failureCount.set(0);
        logger.info("Circuit breaker reset for {}", getName());
    }

    @Override
    public boolean isCircuitBreakerOpen() {
        return circuitBreakerOpen;
    }

    @Override
    public int getFailureCount() {
        return failureCount.get();
    }

    @Override
    public boolean isEnabled() {
        return config.isEnabled();
    }

    @Override
    public NotificationConfig getConfig() {
        return config;
    }

    @Override
    public NotificationConfig.ChannelType getType() {
        return config.getType();
    }

    @Override
    public void reset() {
        resetCircuitBreaker();
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public Instant getCircuitBreakerOpenedAt() {
        long openedAt = circuitBreakerOpenedAt.get();
        return openedAt > 0 ? Instant.ofEpochMilli(openedAt) : null;
    }

    public void shutdown() {
        executorService.shutdownNow();
        try {
            if (!executorService.awaitTermination(1, TimeUnit.SECONDS)) {
                logger.warn("Notifier executor for {} did not terminate cleanly", getName());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
