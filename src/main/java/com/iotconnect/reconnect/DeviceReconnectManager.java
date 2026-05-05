package com.iotconnect.reconnect;

import com.iotconnect.entity.Device;
import com.iotconnect.service.DeviceService;
import com.iotconnect.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Component
public class DeviceReconnectManager {

    private static final Logger logger = LoggerFactory.getLogger(DeviceReconnectManager.class);

    private final DeviceService deviceService;
    private final NotificationService notificationService;

    @Value("${reconnect.enabled:true}")
    private boolean reconnectEnabled;

    @Value("${reconnect.initial-delay-seconds:1}")
    private int initialDelaySeconds;

    @Value("${reconnect.max-delay-seconds:60}")
    private int maxDelaySeconds;

    @Value("${reconnect.multiplier:2.0}")
    private double multiplier;

    @Value("${reconnect.max-attempts:10}")
    private int maxAttempts;

    @Value("${reconnect.jitter:0.1}")
    private double jitter;

    @Value("${reconnect.notification-threshold:3}")
    private int notificationThreshold;

    private ScheduledExecutorService scheduler;
    private final Map<String, ReconnectState> reconnectStates = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private final Set<String> notifiedDevices = ConcurrentHashMap.newKeySet();
    
    private Consumer<String> reconnectSuccessCallback;
    private Consumer<String> reconnectFailureCallback;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    public DeviceReconnectManager(DeviceService deviceService, NotificationService notificationService) {
        this.deviceService = deviceService;
        this.notificationService = notificationService;
    }

    @PostConstruct
    public void init() {
        if (reconnectEnabled) {
            logger.info("Initializing DeviceReconnectManager: initialDelay={}s, maxDelay={}s, multiplier={}, maxAttempts={}, notificationThreshold={}",
                    initialDelaySeconds, maxDelaySeconds, multiplier, maxAttempts, notificationThreshold);
            
            scheduler = Executors.newScheduledThreadPool(4);
            isRunning.set(true);
            
            logger.info("DeviceReconnectManager initialized successfully");
        } else {
            logger.info("DeviceReconnectManager is disabled");
        }
    }

    @PreDestroy
    public void shutdown() {
        logger.info("Shutting down DeviceReconnectManager...");
        
        isRunning.set(false);
        
        for (Map.Entry<String, ScheduledFuture<?>> entry : scheduledTasks.entrySet()) {
            ScheduledFuture<?> future = entry.getValue();
            if (future != null && !future.isDone()) {
                future.cancel(false);
            }
        }
        
        scheduledTasks.clear();
        
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        logger.info("DeviceReconnectManager shut down. Active reconnect tasks: {}", reconnectStates.size());
    }

    public void setReconnectSuccessCallback(Consumer<String> callback) {
        this.reconnectSuccessCallback = callback;
    }

    public void setReconnectFailureCallback(Consumer<String> callback) {
        this.reconnectFailureCallback = callback;
    }

    public void onDeviceDisconnect(String deviceId) {
        if (!reconnectEnabled) {
            logger.debug("Reconnect disabled, skipping for device: {}", deviceId);
            return;
        }

        if (reconnectStates.containsKey(deviceId)) {
            logger.debug("Reconnect already in progress for device: {}", deviceId);
            return;
        }

        ReconnectState state = new ReconnectState(deviceId);
        reconnectStates.put(deviceId, state);
        notifiedDevices.remove(deviceId);
        
        logger.info("Device disconnected, starting reconnect process: deviceId={}", deviceId);
        
        scheduleReconnectAttempt(deviceId, initialDelaySeconds);
    }

    public void onDeviceConnect(String deviceId) {
        ReconnectState state = reconnectStates.remove(deviceId);
        notifiedDevices.remove(deviceId);
        
        if (state != null) {
            cancelScheduledTask(deviceId);
            
            logger.info("Device reconnected successfully: deviceId={}, attempts={}", 
                    deviceId, state.getAttemptCount());
            
            if (reconnectSuccessCallback != null) {
                reconnectSuccessCallback.accept(deviceId);
            }
        }
    }

    public void cancelReconnect(String deviceId) {
        ReconnectState state = reconnectStates.remove(deviceId);
        notifiedDevices.remove(deviceId);
        if (state != null) {
            cancelScheduledTask(deviceId);
            logger.info("Reconnect cancelled for device: {}", deviceId);
        }
    }

    private void scheduleReconnectAttempt(String deviceId, long delaySeconds) {
        if (!isRunning.get()) {
            return;
        }

        cancelScheduledTask(deviceId);

        long actualDelay = calculateDelayWithJitter(delaySeconds);
        
        logger.debug("Scheduling reconnect attempt for device {} in {} seconds", deviceId, actualDelay);

        ScheduledFuture<?> future = scheduler.schedule(
                () -> attemptReconnect(deviceId),
                actualDelay,
                TimeUnit.SECONDS
        );

        scheduledTasks.put(deviceId, future);
    }

    private void attemptReconnect(String deviceId) {
        if (!isRunning.get()) {
            return;
        }

        ReconnectState state = reconnectStates.get(deviceId);
        if (state == null) {
            logger.debug("No reconnect state found for device: {}", deviceId);
            return;
        }

        state.incrementAttempt();
        state.setLastAttemptTime(Instant.now());
        
        logger.info("Attempting to reconnect device: deviceId={}, attempt={}/{}",
                deviceId, state.getAttemptCount(), maxAttempts);

        if (shouldSendNotification(state.getAttemptCount())) {
            sendReconnectNotification(deviceId, state);
        }

        try {
            boolean reconnected = tryEstablishConnection(deviceId);
            
            if (reconnected) {
                onDeviceConnect(deviceId);
                return;
            }
            
        } catch (Exception e) {
            logger.warn("Reconnect attempt failed for device {}: {}", deviceId, e.getMessage());
        }

        if (state.getAttemptCount() >= maxAttempts) {
            handleMaxAttemptsReached(deviceId, state);
            return;
        }

        long nextDelay = calculateNextDelay(state.getAttemptCount());
        state.setNextAttemptDelay(nextDelay);
        
        logger.info("Scheduling next reconnect attempt for device {} in {} seconds (attempt {}/{})",
                deviceId, nextDelay, state.getAttemptCount(), maxAttempts);
        
        scheduleReconnectAttempt(deviceId, nextDelay);
    }

    private boolean shouldSendNotification(int attemptCount) {
        return attemptCount >= notificationThreshold && attemptCount <= maxAttempts;
    }

    private void sendReconnectNotification(String deviceId, ReconnectState state) {
        if (notifiedDevices.contains(deviceId)) {
            logger.debug("Notification already sent for device: {}, skipping", deviceId);
            return;
        }

        var deviceOpt = deviceService.findByDeviceId(deviceId);
        if (deviceOpt.isEmpty()) {
            logger.warn("Device not found for notification: {}", deviceId);
            return;
        }

        Device device = deviceOpt.get();
        
        try {
            String title = "设备重连告警";
            String message = String.format(
                "设备[%s - %s]已离线，重连尝试第%d次/%d次。请检查设备网络连接状态。",
                device.getDeviceId(),
                device.getDeviceName(),
                state.getAttemptCount(),
                maxAttempts
            );

            List<String> channels = new ArrayList<>();
            channels.add("sms");
            channels.add("email");

            notificationService.sendCustomNotification(deviceId, title, message, channels);
            
            notifiedDevices.add(deviceId);
            
            logger.info("Reconnect notification sent for device: {}, attempt: {}", 
                    deviceId, state.getAttemptCount());

        } catch (Exception e) {
            logger.error("Failed to send reconnect notification for device {}: {}", 
                    deviceId, e.getMessage(), e);
        }
    }

    private void handleMaxAttemptsReached(String deviceId, ReconnectState state) {
        logger.warn("Max reconnect attempts reached for device: {}, giving up", deviceId);
        
        var deviceOpt = deviceService.findByDeviceId(deviceId);
        if (deviceOpt.isPresent()) {
            Device device = deviceOpt.get();
            try {
                String title = "设备离线告警";
                String message = String.format(
                    "设备[%s - %s]已离线，重连尝试已达最大次数(%d次)。设备可能已无法连接，请立即检查设备状态。",
                    device.getDeviceId(),
                    device.getDeviceName(),
                    maxAttempts
                );

                List<String> channels = new ArrayList<>();
                channels.add("sms");
                channels.add("email");
                channels.add("webhook");

                notificationService.sendCustomNotification(deviceId, title, message, channels);
                
                logger.info("Max attempts notification sent for device: {}", deviceId);

            } catch (Exception e) {
                logger.error("Failed to send max attempts notification for device {}: {}", 
                        deviceId, e.getMessage(), e);
            }
        }
        
        reconnectStates.remove(deviceId);
        cancelScheduledTask(deviceId);
        
        if (reconnectFailureCallback != null) {
            reconnectFailureCallback.accept(deviceId);
        }
    }

    private boolean tryEstablishConnection(String deviceId) {
        logger.debug("Attempting to establish connection for device: {}", deviceId);
        
        var deviceOpt = deviceService.findByDeviceId(deviceId);
        if (deviceOpt.isEmpty()) {
            logger.warn("Device not found during reconnect: {}", deviceId);
            return false;
        }
        
        return false;
    }

    private long calculateNextDelay(int attempt) {
        double delay = initialDelaySeconds * Math.pow(multiplier, attempt - 1);
        
        if (delay > maxDelaySeconds) {
            delay = maxDelaySeconds;
        }
        
        return (long) delay;
    }

    private long calculateDelayWithJitter(long baseDelay) {
        if (jitter <= 0) {
            return baseDelay;
        }
        
        double jitterAmount = baseDelay * jitter;
        double minDelay = baseDelay - jitterAmount;
        double maxDelay = baseDelay + jitterAmount;
        
        return (long) ThreadLocalRandom.current().nextDouble(minDelay, maxDelay);
    }

    private void cancelScheduledTask(String deviceId) {
        ScheduledFuture<?> future = scheduledTasks.remove(deviceId);
        if (future != null && !future.isDone()) {
            future.cancel(false);
        }
    }

    @Scheduled(fixedDelay = 30000)
    public void cleanupStaleStates() {
        if (!reconnectEnabled) {
            return;
        }

        Instant threshold = Instant.now().minusSeconds(3600);
        
        reconnectStates.entrySet().removeIf(entry -> {
            ReconnectState state = entry.getValue();
            if (state.getLastAttemptTime() != null && 
                state.getLastAttemptTime().isBefore(threshold)) {
                logger.warn("Removing stale reconnect state for device: {}", entry.getKey());
                cancelScheduledTask(entry.getKey());
                notifiedDevices.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    public boolean isReconnectInProgress(String deviceId) {
        return reconnectStates.containsKey(deviceId);
    }

    public ReconnectState getReconnectState(String deviceId) {
        return reconnectStates.get(deviceId);
    }

    public int getActiveReconnectCount() {
        return reconnectStates.size();
    }

    public boolean isReconnectEnabled() {
        return reconnectEnabled;
    }

    public int getNotificationThreshold() {
        return notificationThreshold;
    }

    public Set<String> getNotifiedDevices() {
        return new HashSet<>(notifiedDevices);
    }

    public static class ReconnectState {
        private final String deviceId;
        private int attemptCount;
        private Instant lastAttemptTime;
        private long nextAttemptDelay;
        private final Instant createdAt;

        public ReconnectState(String deviceId) {
            this.deviceId = deviceId;
            this.attemptCount = 0;
            this.createdAt = Instant.now();
        }

        public void incrementAttempt() {
            this.attemptCount++;
        }

        public String getDeviceId() {
            return deviceId;
        }

        public int getAttemptCount() {
            return attemptCount;
        }

        public Instant getLastAttemptTime() {
            return lastAttemptTime;
        }

        public void setLastAttemptTime(Instant lastAttemptTime) {
            this.lastAttemptTime = lastAttemptTime;
        }

        public long getNextAttemptDelay() {
            return nextAttemptDelay;
        }

        public void setNextAttemptDelay(long nextAttemptDelay) {
            this.nextAttemptDelay = nextAttemptDelay;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }
    }
}
