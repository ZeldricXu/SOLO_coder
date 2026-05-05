package com.ratelimiter.service.circuit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class CircuitNotificationService {
    
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Map<String, List<String>> circuitCallbacks;
    private final List<String> globalCallbacks;
    private final ExecutorService executorService;
    private final TaskScheduler taskScheduler;
    
    private final Queue<NotificationQueueItem> pendingQueue;
    private final Queue<NotificationQueueItem> retryQueue;
    private final Cache<String, NotificationQueueItem> completedCache;
    
    private Counter notificationSentCounter;
    private Counter notificationFailedCounter;
    private Counter notificationRetriedCounter;
    private Counter notificationDroppedCounter;
    
    private static final int DEFAULT_MAX_RETRY_COUNT = 5;
    private static final long DEFAULT_RETRY_DELAY_MS = 1000;
    private static final long RETRY_INTERVAL_MS = 5000;
    private static final int MAX_PENDING_QUEUE_SIZE = 10000;
    private static final int COMPLETED_CACHE_SIZE = 1000;
    
    private volatile boolean running;
    private ScheduledFuture<?> retrySchedulerFuture;
    
    public CircuitNotificationService(ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.circuitCallbacks = new ConcurrentHashMap<>();
        this.globalCallbacks = new ArrayList<>();
        this.executorService = Executors.newCachedThreadPool();
        this.taskScheduler = new ConcurrentTaskScheduler();
        
        this.pendingQueue = new ConcurrentLinkedQueue<>();
        this.retryQueue = new ConcurrentLinkedQueue<>();
        this.completedCache = Caffeine.newBuilder()
                .maximumSize(COMPLETED_CACHE_SIZE)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .build();
        
        this.running = true;
    }
    
    @PostConstruct
    public void init() {
        log.info("CircuitNotificationService initializing...");
        
        notificationSentCounter = Counter.builder("circuit_notification_sent_total")
                .description("Total number of circuit notifications sent successfully")
                .register(meterRegistry);
        
        notificationFailedCounter = Counter.builder("circuit_notification_failed_total")
                .description("Total number of circuit notifications failed")
                .register(meterRegistry);
        
        notificationRetriedCounter = Counter.builder("circuit_notification_retried_total")
                .description("Total number of circuit notifications retried")
                .register(meterRegistry);
        
        notificationDroppedCounter = Counter.builder("circuit_notification_dropped_total")
                .description("Total number of circuit notifications dropped after max retries")
                .register(meterRegistry);
        
        Gauge.builder("circuit_notification_pending_queue_size", pendingQueue, Queue::size)
                .description("Number of pending notifications in queue")
                .register(meterRegistry);
        
        Gauge.builder("circuit_notification_retry_queue_size", retryQueue, Queue::size)
                .description("Number of notifications waiting for retry")
                .register(meterRegistry);
        
        startRetryScheduler();
        
        log.info("CircuitNotificationService initialized");
    }
    
    private void startRetryScheduler() {
        retrySchedulerFuture = taskScheduler.scheduleAtFixedRate(
                this::processRetryQueue,
                RETRY_INTERVAL_MS
        );
        log.info("Retry scheduler started with interval: {}ms", RETRY_INTERVAL_MS);
    }
    
    public void registerGlobalCallback(String callbackUrl) {
        synchronized (globalCallbacks) {
            if (!globalCallbacks.contains(callbackUrl)) {
                globalCallbacks.add(callbackUrl);
                log.info("Registered global callback: {}", callbackUrl);
            }
        }
    }
    
    public void unregisterGlobalCallback(String callbackUrl) {
        synchronized (globalCallbacks) {
            globalCallbacks.remove(callbackUrl);
            log.info("Unregistered global callback: {}", callbackUrl);
        }
    }
    
    public void registerCircuitCallback(String circuitId, String callbackUrl) {
        circuitCallbacks.computeIfAbsent(circuitId, k -> new ArrayList<>());
        
        List<String> callbacks = circuitCallbacks.get(circuitId);
        synchronized (callbacks) {
            if (!callbacks.contains(callbackUrl)) {
                callbacks.add(callbackUrl);
                log.info("Registered callback for circuit {}: {}", circuitId, callbackUrl);
            }
        }
    }
    
    public void unregisterCircuitCallback(String circuitId, String callbackUrl) {
        List<String> callbacks = circuitCallbacks.get(circuitId);
        if (callbacks != null) {
            synchronized (callbacks) {
                callbacks.remove(callbackUrl);
                if (callbacks.isEmpty()) {
                    circuitCallbacks.remove(circuitId);
                }
            }
            log.info("Unregistered callback for circuit {}: {}", circuitId, callbackUrl);
        }
    }
    
    public void unregisterAllCallbacksForCircuit(String circuitId) {
        circuitCallbacks.remove(circuitId);
        log.info("Unregistered all callbacks for circuit: {}", circuitId);
    }
    
    public void notifyStateChange(CircuitStateChangeEvent event) {
        log.info("Notifying circuit state change: {} -> {} for circuit: {}", 
                event.getFromState(), event.getToState(), event.getCircuitId());
        
        List<String> allCallbacks = new ArrayList<>();
        
        List<String> circuitSpecificCallbacks = circuitCallbacks.get(event.getCircuitId());
        if (circuitSpecificCallbacks != null) {
            synchronized (circuitSpecificCallbacks) {
                allCallbacks.addAll(circuitSpecificCallbacks);
            }
        }
        
        synchronized (globalCallbacks) {
            allCallbacks.addAll(globalCallbacks);
        }
        
        if (allCallbacks.isEmpty()) {
            log.debug("No callbacks registered for circuit: {}", event.getCircuitId());
            return;
        }
        
        for (String callbackUrl : allCallbacks) {
            NotificationQueueItem item = NotificationQueueItem.create(
                    callbackUrl, event, DEFAULT_MAX_RETRY_COUNT, DEFAULT_RETRY_DELAY_MS
            );
            
            if (pendingQueue.size() >= MAX_PENDING_QUEUE_SIZE) {
                log.warn("Pending queue full, dropping notification for: {}", callbackUrl);
                notificationDroppedCounter.increment();
                continue;
            }
            
            pendingQueue.offer(item);
            
            executorService.submit(() -> processNotification(item));
        }
    }
    
    private void processNotification(NotificationQueueItem item) {
        if (!running) {
            return;
        }
        
        boolean success = sendNotification(item.getCallbackUrl(), item.getEvent());
        
        if (success) {
            item.setCompleted(true);
            pendingQueue.remove(item);
            completedCache.put(item.getId(), item);
            notificationSentCounter.increment();
            log.info("Notification sent successfully to: {}", item.getCallbackUrl());
        } else {
            item.calculateNextRetryTime();
            if (item.canRetry()) {
                pendingQueue.remove(item);
                retryQueue.offer(item);
                notificationRetriedCounter.increment();
                log.warn("Notification failed, queued for retry (attempt {}): {}", 
                        item.getRetryCount(), item.getCallbackUrl());
            } else {
                item.setCompleted(true);
                pendingQueue.remove(item);
                notificationDroppedCounter.increment();
                notificationFailedCounter.increment();
                log.error("Notification dropped after max retries ({}) for: {}", 
                        item.getMaxRetryCount(), item.getCallbackUrl());
            }
        }
    }
    
    @Scheduled(fixedRate = RETRY_INTERVAL_MS)
    public void processRetryQueue() {
        if (!running || retryQueue.isEmpty()) {
            return;
        }
        
        log.debug("Processing retry queue, size: {}", retryQueue.size());
        
        List<NotificationQueueItem> itemsToProcess = new ArrayList<>();
        NotificationQueueItem item;
        
        while ((item = retryQueue.poll()) != null) {
            if (item.isReadyForRetry()) {
                itemsToProcess.add(item);
            } else {
                retryQueue.offer(item);
            }
        }
        
        for (NotificationQueueItem retryItem : itemsToProcess) {
            boolean success = sendNotification(retryItem.getCallbackUrl(), retryItem.getEvent());
            
            if (success) {
                retryItem.setCompleted(true);
                completedCache.put(retryItem.getId(), retryItem);
                notificationSentCounter.increment();
                log.info("Retry notification sent successfully to: {}", retryItem.getCallbackUrl());
            } else {
                retryItem.calculateNextRetryTime();
                if (retryItem.canRetry()) {
                    retryQueue.offer(retryItem);
                    notificationRetriedCounter.increment();
                    log.warn("Retry notification failed, queued for next retry (attempt {}): {}", 
                            retryItem.getRetryCount(), retryItem.getCallbackUrl());
                } else {
                    retryItem.setCompleted(true);
                    notificationDroppedCounter.increment();
                    notificationFailedCounter.increment();
                    log.error("Retry notification dropped after max retries ({}) for: {}", 
                            retryItem.getMaxRetryCount(), retryItem.getCallbackUrl());
                }
            }
        }
    }
    
    private boolean sendNotification(String callbackUrl, CircuitStateChangeEvent event) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            String jsonBody = objectMapper.writeValueAsString(event);
            HttpEntity<String> requestEntity = new HttpEntity<>(jsonBody, headers);
            
            log.debug("Sending notification to {}: {}", callbackUrl, jsonBody);
            
            restTemplate.postForObject(callbackUrl, requestEntity, String.class);
            
            return true;
            
        } catch (Exception e) {
            log.warn("Failed to send notification to {}: {}", callbackUrl, e.getMessage());
            return false;
        }
    }
    
    public List<String> getGlobalCallbacks() {
        synchronized (globalCallbacks) {
            return new ArrayList<>(globalCallbacks);
        }
    }
    
    public List<String> getCircuitCallbacks(String circuitId) {
        List<String> callbacks = circuitCallbacks.get(circuitId);
        if (callbacks != null) {
            synchronized (callbacks) {
                return new ArrayList<>(callbacks);
            }
        }
        return new ArrayList<>();
    }
    
    public int getPendingQueueSize() {
        return pendingQueue.size();
    }
    
    public int getRetryQueueSize() {
        return retryQueue.size();
    }
    
    public void forceRetryAll() {
        log.info("Force retrying all notifications in retry queue...");
        
        List<NotificationQueueItem> itemsToRetry = new ArrayList<>();
        NotificationQueueItem item;
        
        while ((item = retryQueue.poll()) != null) {
            itemsToRetry.add(item);
        }
        
        for (NotificationQueueItem retryItem : itemsToRetry) {
            boolean success = sendNotification(retryItem.getCallbackUrl(), retryItem.getEvent());
            
            if (success) {
                retryItem.setCompleted(true);
                completedCache.put(retryItem.getId(), retryItem);
                notificationSentCounter.increment();
            } else {
                retryItem.calculateNextRetryTime();
                if (retryItem.canRetry()) {
                    retryQueue.offer(retryItem);
                    notificationRetriedCounter.increment();
                }
            }
        }
        
        log.info("Force retry completed");
    }
    
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down CircuitNotificationService...");
        
        running = false;
        
        if (retrySchedulerFuture != null && !retrySchedulerFuture.isDone()) {
            retrySchedulerFuture.cancel(false);
        }
        
        executorService.shutdown();
        
        log.info("CircuitNotificationService shutdown complete. " +
                "Pending: {}, Retry: {}, Dropped: {}", 
                pendingQueue.size(), retryQueue.size(), 
                notificationDroppedCounter.count());
    }
}