package com.datapipeline.notification.suppression;

import com.datapipeline.notification.Notification;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SuppressionStrategy {

    public enum Type {
        RATE_LIMIT,
        DEDUPLICATION,
        QUIET_HOURS,
        THROTTLE
    }

    private final Map<String, Deque<Instant>> rateLimitHistory = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastSentByKey = new ConcurrentHashMap<>();
    private final int maxPerWindow;
    private final Duration windowDuration;
    private final Duration deduplicationWindow;
    private final Set<Integer> quietHours;

    public SuppressionStrategy() {
        this(100, Duration.ofMinutes(1), Duration.ofMinutes(5), Collections.emptySet());
    }

    public SuppressionStrategy(int maxPerWindow, Duration windowDuration,
                               Duration deduplicationWindow, Set<Integer> quietHours) {
        this.maxPerWindow = maxPerWindow;
        this.windowDuration = windowDuration;
        this.deduplicationWindow = deduplicationWindow;
        this.quietHours = new HashSet<>(quietHours);
    }

    public boolean shouldSuppress(Notification notification) {
        if (notification.getPriority() == Notification.Priority.CRITICAL) {
            return false;
        }

        String dedupKey = notification.getDeduplicationKey();
        if (dedupKey != null && !dedupKey.isEmpty()) {
            if (isDuplicate(dedupKey)) {
                return true;
            }
        }

        if (isRateLimited(notification.getSource())) {
            return true;
        }

        if (isQuietHour()) {
            return true;
        }

        recordSent(notification);
        return false;
    }

    private boolean isDuplicate(String dedupKey) {
        Instant lastSent = lastSentByKey.get(dedupKey);
        if (lastSent == null) {
            return false;
        }
        Duration elapsed = Duration.between(lastSent, Instant.now());
        return elapsed.compareTo(deduplicationWindow) < 0;
    }

    private boolean isRateLimited(String source) {
        if (source == null) {
            return false;
        }
        Deque<Instant> history = rateLimitHistory.get(source);
        if (history == null) {
            return false;
        }
        synchronized (history) {
            Instant cutoff = Instant.now().minus(windowDuration);
            while (!history.isEmpty() && history.peekFirst().isBefore(cutoff)) {
                history.pollFirst();
            }
            return history.size() >= maxPerWindow;
        }
    }

    private boolean isQuietHour() {
        if (quietHours.isEmpty()) {
            return false;
        }
        int hour = Instant.now().atZone(java.time.ZoneId.systemDefault()).getHour();
        return quietHours.contains(hour);
    }

    private void recordSent(Notification notification) {
        String source = notification.getSource();
        if (source != null) {
            Deque<Instant> history = rateLimitHistory.computeIfAbsent(source, k -> new ArrayDeque<>());
            synchronized (history) {
                history.addLast(Instant.now());
            }
        }

        String dedupKey = notification.getDeduplicationKey();
        if (dedupKey != null && !dedupKey.isEmpty()) {
            lastSentByKey.put(dedupKey, Instant.now());
        }
    }

}
