package com.enterprise.risk.alert;

import com.enterprise.risk.common.alert.AlertEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
@Component
@RequiredArgsConstructor
public class AlertConvergenceManager {

    @Value("${risk.alert.convergence.window-seconds:300}")
    private int defaultWindowSeconds;

    @Value("${risk.alert.convergence.scheduler-interval-seconds:10}")
    private int schedulerIntervalSeconds;

    private final AlertAggregator aggregator;

    private final Map<String, ConvergenceWindow> windowStore = new ConcurrentHashMap<>();

    private final Map<String, Object> windowLocks = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "alert-convergence-scheduler");
        t.setDaemon(true);
        return t;
    });

    private volatile Consumer<AlertEvent> windowCloseCallback;

    public void start() {
        scheduler.scheduleAtFixedRate(
                this::checkAndCloseWindows,
                schedulerIntervalSeconds,
                schedulerIntervalSeconds,
                TimeUnit.SECONDS
        );
        log.info("告警收敛管理器已启动，窗口默认={}秒, 检查间隔={}秒",
                defaultWindowSeconds, schedulerIntervalSeconds);
    }

    public void setWindowCloseCallback(Consumer<AlertEvent> callback) {
        this.windowCloseCallback = callback;
    }

    public AlertEvent record(String fingerprint,
                             AlertEvent alert,
                             Integer customWindowSeconds) {
        int windowSeconds = customWindowSeconds != null && customWindowSeconds > 0
                ? customWindowSeconds
                : defaultWindowSeconds;

        Object lock = windowLocks.computeIfAbsent(fingerprint, k -> new Object());
        synchronized (lock) {
            ConvergenceWindow window = windowStore.get(fingerprint);

            long now = System.currentTimeMillis();
            if (window == null || isWindowExpired(window, now)) {
                if (window != null) {
                    closeWindow(window);
                }
                window = createWindow(fingerprint, windowSeconds, now);
                windowStore.put(fingerprint, window);
            }

            window.lastActivityTime = now;
            window.alert = alert;

            if (log.isDebugEnabled()) {
                log.debug("收敛窗口记录: fingerprint={}, 窗口结束时间={}, 剩余={}ms",
                        fingerprint,
                        window.endTime,
                        Math.max(0, window.endTime - now));
            }

            return alert;
        }
    }

    public AlertEvent forceClose(String fingerprint) {
        Object lock = windowLocks.computeIfAbsent(fingerprint, k -> new Object());
        synchronized (lock) {
            ConvergenceWindow window = windowStore.remove(fingerprint);
            if (window == null) {
                return null;
            }
            return closeWindow(window);
        }
    }

    public List<AlertEvent> forceCloseAll() {
        List<AlertEvent> closed = new ArrayList<>();
        for (String fingerprint : new ArrayList<>(windowStore.keySet())) {
            AlertEvent alert = forceClose(fingerprint);
            if (alert != null) {
                closed.add(alert);
            }
        }
        log.info("强制关闭所有收敛窗口: {} 个", closed.size());
        return closed;
    }

    public ConvergenceStatus getStatus(String fingerprint) {
        ConvergenceWindow window = windowStore.get(fingerprint);
        if (window == null) {
            return null;
        }

        ConvergenceStatus status = new ConvergenceStatus();
        status.fingerprint = fingerprint;
        status.startTime = window.startTime;
        status.endTime = window.endTime;
        status.lastActivityTime = window.lastActivityTime;
        status.windowSeconds = window.windowSeconds;
        status.isOpen = !isWindowExpired(window, System.currentTimeMillis());
        status.remainingMs = Math.max(0, window.endTime - System.currentTimeMillis());
        status.ruleHitCount = window.alert != null ? window.alert.getRuleHitCount() : 0;
        status.eventCount = window.alert != null ? window.alert.getEventCount() : 0;
        return status;
    }

    public int getOpenWindowCount() {
        return windowStore.size();
    }

    public void checkAndCloseWindows() {
        long now = System.currentTimeMillis();
        List<String> toClose = new ArrayList<>();

        for (Map.Entry<String, ConvergenceWindow> entry : windowStore.entrySet()) {
            if (isWindowExpired(entry.getValue(), now)) {
                toClose.add(entry.getKey());
            }
        }

        for (String fingerprint : toClose) {
            try {
                forceClose(fingerprint);
            } catch (Exception e) {
                log.warn("关闭收敛窗口异常: {}", fingerprint, e);
            }
        }

        if (!toClose.isEmpty() && log.isDebugEnabled()) {
            log.debug("本次清理收敛窗口: {} 个", toClose.size());
        }
    }

    private ConvergenceWindow createWindow(String fingerprint,
                                           int windowSeconds,
                                           long now) {
        ConvergenceWindow window = new ConvergenceWindow();
        window.fingerprint = fingerprint;
        window.startTime = now;
        window.endTime = now + (long) windowSeconds * 1000L;
        window.lastActivityTime = now;
        window.windowSeconds = windowSeconds;
        return window;
    }

    private AlertEvent closeWindow(ConvergenceWindow window) {
        AlertEvent alert = aggregator.removeAndGet(window.fingerprint);
        if (alert != null) {
            if (alert.getMetadata() == null) {
                alert.setMetadata(new ConcurrentHashMap<>());
            }
            alert.getMetadata().put("convergence_window_start", window.startTime);
            alert.getMetadata().put("convergence_window_end", window.endTime);
            alert.getMetadata().put("convergence_window_seconds", window.windowSeconds);

            log.info("收敛窗口关闭: fingerprint={}, 告警ID={}, 命中次数={}, 事件数={}",
                    window.fingerprint,
                    alert.getAlertId(),
                    alert.getRuleHitCount(),
                    alert.getEventCount());

            if (windowCloseCallback != null) {
                try {
                    windowCloseCallback.accept(alert);
                } catch (Exception e) {
                    log.warn("窗口关闭回调执行异常: {}", alert.getAlertId(), e);
                }
            }
        }
        return alert;
    }

    private boolean isWindowExpired(ConvergenceWindow window, long now) {
        return now > window.endTime;
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        int remaining = windowStore.size();
        if (remaining > 0) {
            log.warn("收敛管理器关闭时仍有 {} 个活跃窗口", remaining);
        }
        log.info("告警收敛管理器已关闭");
    }

    public int getDefaultWindowSeconds() {
        return defaultWindowSeconds;
    }

    public void setDefaultWindowSeconds(int seconds) {
        this.defaultWindowSeconds = seconds;
    }

    private static class ConvergenceWindow {
        String fingerprint;
        long startTime;
        long endTime;
        long lastActivityTime;
        int windowSeconds;
        AlertEvent alert;
    }

    public static class ConvergenceStatus {
        public String fingerprint;
        public long startTime;
        public long endTime;
        public long lastActivityTime;
        public int windowSeconds;
        public boolean isOpen;
        public long remainingMs;
        public int ruleHitCount;
        public int eventCount;

        public Instant getStartTimeAsInstant() {
            return Instant.ofEpochMilli(startTime);
        }

        public Instant getEndTimeAsInstant() {
            return Instant.ofEpochMilli(endTime);
        }
    }
}
