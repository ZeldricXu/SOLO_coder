package com.solocoder.infrastructure.adapter.logging.plugin;

import com.solocoder.domain.port.StructuredLoggerPort;
import com.solocoder.infrastructure.adapter.logging.StructuredLogEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
public class ErrorAlertLogPlugin implements LogPlugin {

    private static final long ALERT_THRESHOLD = 10;
    private static final long ALERT_WINDOW_MS = 60000;

    private final AtomicLong errorCount = new AtomicLong(0);
    private final AtomicLong windowStart = new AtomicLong(Instant.now().toEpochMilli());

    private final StructuredLoggerPort logger;

    @Override
    public void afterLog(StructuredLogEvent event) {
        if (!"ERROR".equals(event.getLevel())) {
            return;
        }

        long now = Instant.now().toEpochMilli();
        long window = windowStart.get();

        if (now - window > ALERT_WINDOW_MS) {
            if (windowStart.compareAndSet(window, now)) {
                errorCount.set(0);
            }
        }

        long count = errorCount.incrementAndGet();
        if (count >= ALERT_THRESHOLD) {
            logger.warn("错误率过高告警", java.util.Map.of(
                    "alertType", "HIGH_ERROR_RATE",
                    "errorCount", count,
                    "windowMs", ALERT_WINDOW_MS
            ));
        }
    }

    @Override
    public boolean supports(String level) {
        return "ERROR".equals(level);
    }

    @Override
    public int getOrder() {
        return 200;
    }
}
