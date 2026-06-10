package com.company.dbstudio.connection.ssh;

import com.company.dbstudio.connection.model.SshConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class SshTunnelHealthChecker implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SshTunnelHealthChecker.class);
    private static final SshTunnelHealthChecker INSTANCE = new SshTunnelHealthChecker();

    private static final int DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 30;
    private static final int DEFAULT_MAX_RECONNECT_ATTEMPTS = 5;
    private static final int DEFAULT_RECONNECT_DELAY_SECONDS = 5;

    private final ScheduledExecutorService scheduler;
    private final Map<String, ScheduledFuture<?>> checkTasks;
    private final Map<String, TunnelHealthStatus> healthStatuses;
    private final Map<String, Consumer<TunnelReconnectEvent>> reconnectListeners;
    private final AtomicBoolean running;

    public static class TunnelReconnectEvent {
        private final String connectionId;
        private final String sshHost;
        private final int attempt;
        private final int maxAttempts;
        private final boolean success;
        private final String message;
        private final LocalDateTime timestamp;

        public TunnelReconnectEvent(String connectionId, String sshHost, int attempt,
                                    int maxAttempts, boolean success, String message) {
            this.connectionId = connectionId;
            this.sshHost = sshHost;
            this.attempt = attempt;
            this.maxAttempts = maxAttempts;
            this.success = success;
            this.message = message;
            this.timestamp = LocalDateTime.now();
        }

        public String getConnectionId() { return connectionId; }
        public String getSshHost() { return sshHost; }
        public int getAttempt() { return attempt; }
        public int getMaxAttempts() { return maxAttempts; }
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public LocalDateTime getTimestamp() { return timestamp; }

        @Override
        public String toString() {
            return String.format("[%s] %s - 尝试 %d/%d: %s",
                    connectionId, success ? "重连成功" : "重连失败",
                    attempt, maxAttempts, message);
        }
    }

    public static class TunnelHealthStatus {
        private final String connectionId;
        private final AtomicInteger reconnectAttempts;
        private final AtomicBoolean isReconnecting;
        private volatile LocalDateTime lastHealthCheck;
        private volatile LocalDateTime lastSuccessfulCheck;
        private volatile boolean healthy;
        private volatile String lastErrorMessage;

        public TunnelHealthStatus(String connectionId) {
            this.connectionId = connectionId;
            this.reconnectAttempts = new AtomicInteger(0);
            this.isReconnecting = new AtomicBoolean(false);
            this.lastHealthCheck = LocalDateTime.now();
            this.lastSuccessfulCheck = LocalDateTime.now();
            this.healthy = true;
        }

        public String getConnectionId() { return connectionId; }
        public int getReconnectAttempts() { return reconnectAttempts.get(); }
        public boolean isReconnecting() { return isReconnecting.get(); }
        public LocalDateTime getLastHealthCheck() { return lastHealthCheck; }
        public LocalDateTime getLastSuccessfulCheck() { return lastSuccessfulCheck; }
        public boolean isHealthy() { return healthy; }
        public String getLastErrorMessage() { return lastErrorMessage; }

        public void incrementReconnectAttempts() { reconnectAttempts.incrementAndGet(); }
        public void resetReconnectAttempts() { reconnectAttempts.set(0); }
        public void setReconnecting(boolean reconnecting) { isReconnecting.set(reconnecting); }
        public void setHealthy(boolean healthy) {
            this.healthy = healthy;
            this.lastHealthCheck = LocalDateTime.now();
            if (healthy) {
                this.lastSuccessfulCheck = LocalDateTime.now();
                this.lastErrorMessage = null;
            }
        }
        public void setLastErrorMessage(String message) {
            this.lastErrorMessage = message;
        }
    }

    private SshTunnelHealthChecker() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ssh-health-checker");
            t.setDaemon(true);
            return t;
        });
        this.checkTasks = new ConcurrentHashMap<>();
        this.healthStatuses = new ConcurrentHashMap<>();
        this.reconnectListeners = new ConcurrentHashMap<>();
        this.running = new AtomicBoolean(true);
    }

    public static SshTunnelHealthChecker getInstance() {
        return INSTANCE;
    }

    public void startMonitoring(String connectionId, SshTunnel tunnel, SshConfig config) {
        if (!running.get()) return;

        healthStatuses.putIfAbsent(connectionId, new TunnelHealthStatus(connectionId));

        ScheduledFuture<?> existing = checkTasks.get(connectionId);
        if (existing != null && !existing.isDone()) {
            existing.cancel(false);
        }

        int interval = config.getKeepAliveInterval() > 0 
                ? Math.max(10, config.getKeepAliveInterval() / 1000) 
                : DEFAULT_HEARTBEAT_INTERVAL_SECONDS;

        logger.info("开始监控SSH隧道健康状态: {}, 心跳间隔: {}秒", connectionId, interval);

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> performHealthCheck(connectionId, tunnel, config),
                interval, interval, TimeUnit.SECONDS
        );

        checkTasks.put(connectionId, future);
    }

    public void stopMonitoring(String connectionId) {
        ScheduledFuture<?> task = checkTasks.remove(connectionId);
        if (task != null) {
            task.cancel(false);
            logger.info("停止监控SSH隧道: {}", connectionId);
        }
        healthStatuses.remove(connectionId);
        reconnectListeners.remove(connectionId);
    }

    public void addReconnectListener(String connectionId, Consumer<TunnelReconnectEvent> listener) {
        reconnectListeners.put(connectionId, listener);
    }

    public void removeReconnectListener(String connectionId) {
        reconnectListeners.remove(connectionId);
    }

    public TunnelHealthStatus getHealthStatus(String connectionId) {
        return healthStatuses.get(connectionId);
    }

    public boolean isTunnelHealthy(String connectionId) {
        TunnelHealthStatus status = healthStatuses.get(connectionId);
        return status != null && status.isHealthy();
    }

    private void performHealthCheck(String connectionId, SshTunnel tunnel, SshConfig config) {
        if (!running.get()) return;

        TunnelHealthStatus status = healthStatuses.get(connectionId);
        if (status == null) {
            stopMonitoring(connectionId);
            return;
        }

        try {
            boolean isOpen = tunnel.isOpen();
            boolean heartbeatOk = isOpen && sendHeartbeat(tunnel);

            status.setHealthy(heartbeatOk);

            if (!heartbeatOk) {
                logger.warn("SSH隧道健康检查失败: {}, 开始自动重连", connectionId);
                status.setLastErrorMessage("心跳检测失败");
                attemptReconnect(connectionId, tunnel, config, status);
            } else {
                status.resetReconnectAttempts();
            }

        } catch (Exception e) {
            logger.error("SSH隧道健康检查异常: {}", connectionId, e);
            status.setHealthy(false);
            status.setLastErrorMessage(e.getMessage());
            attemptReconnect(connectionId, tunnel, config, status);
        }
    }

    private boolean sendHeartbeat(SshTunnel tunnel) {
        try {
            com.jcraft.jsch.Session session = tunnel.getSession();
            if (session == null || !session.isConnected()) {
                return false;
            }
            session.sendKeepAliveMsg();
            return true;
        } catch (Exception e) {
            logger.debug("发送SSH心跳失败: {}", e.getMessage());
            return false;
        }
    }

    private void attemptReconnect(String connectionId, SshTunnel oldTunnel, SshConfig config,
                                  TunnelHealthStatus status) {
        if (!status.isReconnecting().compareAndSet(false, true)) {
            logger.debug("已经在重连中: {}", connectionId);
            return;
        }

        try {
            int maxAttempts = DEFAULT_MAX_RECONNECT_ATTEMPTS;
            int attempts = status.getReconnectAttempts();

            while (running.get() && attempts < maxAttempts) {
                attempts++;
                status.incrementReconnectAttempts();

                notifyReconnectListeners(new TunnelReconnectEvent(
                        connectionId, oldTunnel.getSshHost(),
                        attempts, maxAttempts, false,
                        "正在尝试重新连接... (" + attempts + "/" + maxAttempts + ")"
                ));

                logger.info("SSH隧道重连尝试 {}/{}: {}", attempts, maxAttempts, connectionId);

                try {
                    SshTunnelManager.getInstance().closeTunnel(connectionId);

                    SshConfig reconnectConfig = config.copy();
                    SshTunnel newTunnel = SshTunnelManager.getInstance()
                            .createTunnel(connectionId, reconnectConfig);

                    if (newTunnel != null && newTunnel.isOpen()) {
                        status.setHealthy(true);
                        status.resetReconnectAttempts();
                        status.setReconnecting(false);

                        notifyReconnectListeners(new TunnelReconnectEvent(
                                connectionId, oldTunnel.getSshHost(),
                                attempts, maxAttempts, true,
                                "SSH隧道重连成功"
                        ));

                        logger.info("SSH隧道重连成功: {}, 尝试次数: {}", connectionId, attempts);

                        oldTunnel.updateFromNewTunnel(newTunnel);

                        return;
                    }

                } catch (Exception e) {
                    logger.warn("SSH隧道重连失败 (尝试 {}/{}): {}", 
                            attempts, maxAttempts, connectionId, e);
                    status.setLastErrorMessage(e.getMessage());

                    notifyReconnectListeners(new TunnelReconnectEvent(
                            connectionId, oldTunnel.getSshHost(),
                            attempts, maxAttempts, false,
                            "重连失败: " + e.getMessage()
                    ));
                }

                if (attempts < maxAttempts) {
                    Thread.sleep(DEFAULT_RECONNECT_DELAY_SECONDS * 1000L);
                }
            }

            status.setReconnecting(false);
            notifyReconnectListeners(new TunnelReconnectEvent(
                    connectionId, oldTunnel.getSshHost(),
                    maxAttempts, maxAttempts, false,
                    "已达到最大重连次数，请手动重新连接"
            ));
            logger.error("SSH隧道重连失败，已达到最大尝试次数: {}", connectionId);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status.setReconnecting(false);
            logger.info("SSH隧道重连被中断: {}", connectionId);
        } catch (Exception e) {
            status.setReconnecting(false);
            logger.error("SSH隧道重连过程发生异常: {}", connectionId, e);
        }
    }

    private void notifyReconnectListeners(TunnelReconnectEvent event) {
        Consumer<TunnelReconnectEvent> listener = reconnectListeners.get(event.getConnectionId());
        if (listener != null) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                logger.error("重连事件监听器异常", e);
            }
        }
    }

    public void triggerManualReconnect(String connectionId, SshTunnel tunnel, SshConfig config) {
        TunnelHealthStatus status = healthStatuses.get(connectionId);
        if (status == null) {
            status = new TunnelHealthStatus(connectionId);
            healthStatuses.put(connectionId, status);
        }
        status.resetReconnectAttempts();
        status.setReconnecting(false);
        attemptReconnect(connectionId, tunnel, config, status);
    }

    @Override
    public void close() {
        running.set(false);
        for (ScheduledFuture<?> task : checkTasks.values()) {
            task.cancel(false);
        }
        checkTasks.clear();
        healthStatuses.clear();
        reconnectListeners.clear();
        scheduler.shutdownNow();
        logger.info("SSH隧道健康检查器已关闭");
    }
}
