package com.solocoder.platform.notification.monitor;

import io.micrometer.core.instrument.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class NotificationMonitor {

    private final MeterRegistry meterRegistry;
    private final Counter sentCounter;
    private final Counter failedCounter;
    private final Timer sendTimer;
    private final Counter rateLimitedCounter;
    private final ConcurrentHashMap<String, AtomicLong> channelActiveCounters = new ConcurrentHashMap<>();

    public NotificationMonitor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.sentCounter = Counter.builder("notification_sent_total")
                .description("Total number of notifications sent")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("notification_failed_total")
                .description("Total number of notifications failed")
                .register(meterRegistry);
        this.rateLimitedCounter = Counter.builder("notification_rate_limited_total")
                .description("Total number of rate-limited notifications")
                .register(meterRegistry);
        this.sendTimer = Timer.builder("notification_send_duration")
                .description("Time spent sending notifications")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(meterRegistry);
        log.info("Notification monitor initialized with Prometheus metrics");
    }

    public void recordSend(String channel, long durationMs, boolean success) {
        sendTimer.record(durationMs, TimeUnit.MILLISECONDS);
        if (success) {
            sentCounter.increment();
            Counter.builder("notification_channel_sent_total")
                    .tag("channel", channel)
                    .register(meterRegistry).increment();
        } else {
            failedCounter.increment();
            Counter.builder("notification_channel_failed_total")
                    .tag("channel", channel)
                    .register(meterRegistry).increment();
        }
        Timer.builder("notification_channel_duration")
                .tag("channel", channel)
                .register(meterRegistry).record(durationMs, TimeUnit.MILLISECONDS);
        log.debug("Recorded notification metric: channel={}, duration={}ms, success={}", channel, durationMs, success);
    }

    public void recordRateLimited(String channel) {
        rateLimitedCounter.increment();
        log.debug("Recorded rate limit: channel={}", channel);
    }

    public void recordTemplateRender(String templateId, long durationMs) {
        Timer.builder("notification_template_render_duration")
                .tag("template", templateId)
                .register(meterRegistry).record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordActiveChannel(String channel) {
        AtomicLong counter = channelActiveCounters.computeIfAbsent(channel,
                k -> meterRegistry.gauge("notification_channel_active",
                        Tags.of("channel", channel), new AtomicLong(0)));
        counter.incrementAndGet();
    }

    public void deactivateChannel(String channel) {
        AtomicLong counter = channelActiveCounters.get(channel);
        if (counter != null) {
            counter.decrementAndGet();
        }
    }
}
