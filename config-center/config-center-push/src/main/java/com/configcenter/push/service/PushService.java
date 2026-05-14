package com.configcenter.push.service;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.alibaba.fastjson.JSON;
import com.configcenter.common.dto.*;
import com.configcenter.common.entity.*;
import com.configcenter.common.enums.*;
import com.configcenter.common.exception.BusinessException;
import com.configcenter.common.util.EntityConverter;
import com.configcenter.group.repository.ApplicationInstanceRepository;
import com.configcenter.group.repository.ConfigGroupRepository;
import com.configcenter.push.config.PushProperties;
import com.configcenter.push.repository.PushRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class PushService {

    private final PushRecordRepository pushRecordRepository;
    private final ConfigGroupRepository configGroupRepository;
    private final ApplicationInstanceRepository instanceRepository;
    private final PushProperties pushProperties;
    
    private ThreadPoolTaskExecutor asyncPushExecutor;
    private ThreadPoolTaskExecutor pushWorkerExecutor;
    
    private final Map<String, PushTaskStatus> runningTasks = new ConcurrentHashMap<>();
    private final AtomicLong totalPushed = new AtomicLong(0);
    private final AtomicLong totalFailed = new AtomicLong(0);
    
    @PostConstruct
    public void init() {
        initAsyncPushExecutor();
        initPushWorkerExecutor();
        log.info("PushService initialized with asyncEnabled={}, defaultParallelism={}", 
                pushProperties.getAsync().getEnabled(), 
                pushProperties.getParallelPushCount());
    }
    
    private void initAsyncPushExecutor() {
        asyncPushExecutor = new ThreadPoolTaskExecutor();
        asyncPushExecutor.setCorePoolSize(pushProperties.getAsync().getCorePoolSize());
        asyncPushExecutor.setMaxPoolSize(pushProperties.getAsync().getMaxPoolSize());
        asyncPushExecutor.setQueueCapacity(pushProperties.getAsync().getQueueCapacity());
        asyncPushExecutor.setKeepAliveSeconds(pushProperties.getAsync().getKeepAliveSeconds());
        asyncPushExecutor.setThreadNamePrefix("async-push-");
        asyncPushExecutor.setRejectedExecutionHandler((r, executor) -> {
            log.warn("Async push queue is full, executing in caller thread");
            if (!executor.isShutdown()) {
                r.run();
            }
        });
        asyncPushExecutor.initialize();
    }
    
    private void initPushWorkerExecutor() {
        pushWorkerExecutor = new ThreadPoolTaskExecutor();
        pushWorkerExecutor.setCorePoolSize(5);
        pushWorkerExecutor.setMaxPoolSize(pushProperties.getMaxParallelCount());
        pushWorkerExecutor.setQueueCapacity(10000);
        pushWorkerExecutor.setKeepAliveSeconds(60);
        pushWorkerExecutor.setThreadNamePrefix("push-worker-");
        pushWorkerExecutor.initialize();
    }

    @Transactional
    public PushResultDTO pushConfig(String configId, String version, String groupId, String pushBy) {
        log.info("Push requested: configId={}, version={}, groupId={}, asyncEnabled={}", 
                configId, version, groupId, pushProperties.getAsync().getEnabled());

        ConfigGroup group = configGroupRepository.findByGroupIdAndDeletedFalse(groupId)
                .orElseThrow(() -> new BusinessException("分组不存在: " + groupId));

        List<String> applications = group.getApplications();
        List<ApplicationInstance> instances = new ArrayList<>();
        if (applications != null && !applications.isEmpty()) {
            instances = instanceRepository.findByApplicationsAndStatus(applications, InstanceStatus.ONLINE);
        }

        PushRecord record = new PushRecord();
        record.setConfigId(configId);
        record.setVersion(version);
        record.setTargetGroup(groupId);
        record.setPushStatus(PushStatus.PENDING);
        record.setTotalCount(instances.size());
        record.setPushBy(pushBy);
        PushRecord saved = pushRecordRepository.save(record);

        if (!pushProperties.getEnabled()) {
            log.warn("Push is disabled, marking as completed without actual push");
            saved.setPushStatus(PushStatus.COMPLETED);
            saved.setCompleteTime(LocalDateTime.now());
            pushRecordRepository.save(saved);
            return EntityConverter.toPushResultDTO(saved);
        }

        if (instances.isEmpty()) {
            log.warn("No online instances found for group: {}", groupId);
            saved.setPushStatus(PushStatus.COMPLETED);
            saved.setCompleteTime(LocalDateTime.now());
            pushRecordRepository.save(saved);
            return EntityConverter.toPushResultDTO(saved);
        }

        saved.setPushStatus(PushStatus.PUSHING);
        pushRecordRepository.save(saved);
        
        int parallelism = pushProperties.getParallelismForGroup(groupId, instances.size());
        log.info("Push task created: pushId={}, instances={}, parallelism={}", 
                saved.getPushId(), instances.size(), parallelism);

        PushTaskContext context = PushTaskContext.builder()
                .pushId(saved.getPushId())
                .configId(configId)
                .version(version)
                .groupId(groupId)
                .instances(instances)
                .parallelism(parallelism)
                .build();

        if (Boolean.TRUE.equals(pushProperties.getAsync().getEnabled())) {
            submitAsyncPush(context);
        } else {
            doPushSync(context);
        }

        return EntityConverter.toPushResultDTO(saved);
    }
    
    private void submitAsyncPush(PushTaskContext context) {
        PushTaskStatus taskStatus = PushTaskStatus.builder()
                .pushId(context.getPushId())
                .status(PushStatus.PUSHING)
                .startTime(LocalDateTime.now())
                .totalCount(context.getInstances().size())
                .build();
        runningTasks.put(context.getPushId(), taskStatus);
        
        asyncPushExecutor.submit(() -> {
            try {
                log.info("Starting async push task: pushId={}", context.getPushId());
                doPushSync(context);
            } catch (Exception e) {
                log.error("Async push task failed: pushId={}", context.getPushId(), e);
                updatePushStatus(context.getPushId(), PushStatus.FAILED, 0, context.getInstances().size());
            } finally {
                runningTasks.remove(context.getPushId());
            }
        });
        
        log.info("Async push task submitted: pushId={}", context.getPushId());
    }

    private void doPushSync(PushTaskContext context) {
        log.info("Executing push: pushId={}, instances={}, parallelism={}", 
                context.getPushId(), context.getInstances().size(), context.getParallelism());

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        
        Map<String, Object> pushMessage = new HashMap<>();
        pushMessage.put("configId", context.getConfigId());
        pushMessage.put("version", context.getVersion());
        pushMessage.put("pushTime", LocalDateTime.now().toString());

        String jsonMessage = JSON.toJSONString(pushMessage);
        
        ExecutorService batchExecutor = Executors.newFixedThreadPool(context.getParallelism());
        List<Future<PushResult>> futures = new ArrayList<>();
        Semaphore semaphore = new Semaphore(context.getParallelism());

        try {
            for (ApplicationInstance instance : context.getInstances()) {
                semaphore.acquire();
                
                Future<PushResult> future = batchExecutor.submit(() -> {
                    try {
                        boolean success = pushToInstanceWithRetry(instance, jsonMessage);
                        if (success) {
                            successCount.incrementAndGet();
                            totalPushed.incrementAndGet();
                        } else {
                            failCount.incrementAndGet();
                            totalFailed.incrementAndGet();
                        }
                        return new PushResult(instance.getInstanceId(), success, null);
                    } catch (Exception e) {
                        log.error("Push to instance failed: {}", instance.getInstanceAddress(), e);
                        failCount.incrementAndGet();
                        totalFailed.incrementAndGet();
                        return new PushResult(instance.getInstanceId(), false, e.getMessage());
                    } finally {
                        semaphore.release();
                    }
                });
                futures.add(future);
            }

            for (Future<PushResult> future : futures) {
                try {
                    future.get(pushProperties.getPushTimeoutSeconds() * 2L, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.warn("Future execution timeout", e);
                    failCount.incrementAndGet();
                }
            }

        } catch (InterruptedException e) {
            log.error("Push execution interrupted", e);
            Thread.currentThread().interrupt();
        } finally {
            batchExecutor.shutdown();
            try {
                if (!batchExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                    batchExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                batchExecutor.shutdownNow();
            }
        }

        updatePushStatus(context.getPushId(), successCount.get(), failCount.get());
        
        log.info("Push completed: pushId={}, success={}, fail={}", 
                context.getPushId(), successCount.get(), failCount.get());
    }

    private boolean pushToInstanceWithRetry(ApplicationInstance instance, String jsonMessage) {
        int maxRetry = pushProperties.getMaxRetryCount();
        int retryCount = 0;
        Exception lastException = null;
        
        while (retryCount <= maxRetry) {
            try {
                boolean success = pushToInstance(instance, jsonMessage);
                if (success) {
                    return true;
                }
                if (retryCount < maxRetry) {
                    log.warn("Push failed to {}, retry {}/{}", instance.getInstanceAddress(), retryCount + 1, maxRetry);
                    Thread.sleep(pushProperties.getRetryIntervalSeconds() * 1000L);
                }
            } catch (Exception e) {
                lastException = e;
                if (retryCount < maxRetry) {
                    log.warn("Push exception to {}, retry {}/{}: {}", 
                            instance.getInstanceAddress(), retryCount + 1, maxRetry, e.getMessage());
                    try {
                        Thread.sleep(pushProperties.getRetryIntervalSeconds() * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            retryCount++;
        }
        
        if (lastException != null) {
            log.error("Push finally failed to {} after {} retries: {}", 
                    instance.getInstanceAddress(), maxRetry, lastException.getMessage());
        }
        return false;
    }

    private boolean pushToInstance(ApplicationInstance instance, String jsonMessage) {
        String url = "http://" + instance.getInstanceAddress() + pushProperties.getPushEndpoint();
        try {
            HttpResponse response = HttpRequest.post(url)
                    .body(jsonMessage)
                    .contentType("application/json")
                    .timeout(pushProperties.getPushTimeoutSeconds() * 1000)
                    .execute();

            if (response.isOk()) {
                log.debug("Push success to instance: {}", instance.getInstanceAddress());
                instance.setLastConfigSync(LocalDateTime.now());
                instanceRepository.save(instance);
                return true;
            } else {
                log.debug("Push failed to instance: {}, status: {}", 
                        instance.getInstanceAddress(), response.getStatus());
                return false;
            }
        } catch (Exception e) {
            log.debug("Push exception to instance: {}", instance.getInstanceAddress(), e);
            return false;
        }
    }
    
    @Transactional
    public void updatePushStatus(String pushId, int successCount, int failCount) {
        PushRecord record = pushRecordRepository.findById(pushId).orElse(null);
        if (record == null) {
            log.warn("Push record not found: {}", pushId);
            return;
        }
        
        record.setSuccessCount(successCount);
        record.setFailCount(failCount);
        record.setCompleteTime(LocalDateTime.now());

        if (failCount == 0) {
            record.setPushStatus(PushStatus.COMPLETED);
        } else if (successCount == 0) {
            record.setPushStatus(PushStatus.FAILED);
        } else {
            record.setPushStatus(PushStatus.PARTIAL_FAILED);
        }

        pushRecordRepository.save(record);
    }

    @Transactional
    public void updatePushStatus(String pushId, PushStatus status, int successCount, int failCount) {
        PushRecord record = pushRecordRepository.findById(pushId).orElse(null);
        if (record == null) {
            log.warn("Push record not found: {}", pushId);
            return;
        }
        
        record.setSuccessCount(successCount);
        record.setFailCount(failCount);
        record.setPushStatus(status);
        if (status == PushStatus.COMPLETED || status == PushStatus.FAILED || status == PushStatus.PARTIAL_FAILED) {
            record.setCompleteTime(LocalDateTime.now());
        }
        
        pushRecordRepository.save(record);
    }

    public PushResultDTO getPushRecord(String pushId) {
        PushRecord record = pushRecordRepository.findById(pushId)
                .orElseThrow(() -> new BusinessException("推送记录不存在: " + pushId));
        return EntityConverter.toPushResultDTO(record);
    }
    
    public Map<String, Object> getRunningTaskStatus(String pushId) {
        PushTaskStatus status = runningTasks.get(pushId);
        if (status != null) {
            Map<String, Object> result = new HashMap<>();
            result.put("pushId", status.getPushId());
            result.put("status", status.getStatus().name());
            result.put("startTime", status.getStartTime().toString());
            result.put("totalCount", status.getTotalCount());
            result.put("isRunning", true);
            return result;
        }
        return null;
    }
    
    public List<Map<String, Object>> getAllRunningTasks() {
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (PushTaskStatus status : runningTasks.values()) {
            Map<String, Object> task = new HashMap<>();
            task.put("pushId", status.getPushId());
            task.put("status", status.getStatus().name());
            task.put("startTime", status.getStartTime().toString());
            task.put("totalCount", status.getTotalCount());
            tasks.add(task);
        }
        return tasks;
    }
    
    public Map<String, Object> getPushStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalPushed", totalPushed.get());
        stats.put("totalFailed", totalFailed.get());
        stats.put("runningTasks", runningTasks.size());
        stats.put("asyncQueueSize", asyncPushExecutor.getThreadPoolExecutor().getQueue().size());
        stats.put("workerQueueSize", pushWorkerExecutor.getThreadPoolExecutor().getQueue().size());
        stats.put("defaultParallelism", pushProperties.getParallelPushCount());
        stats.put("maxParallelism", pushProperties.getMaxParallelCount());
        return stats;
    }

    public List<PushResultDTO> getPushRecordsByConfig(String configId) {
        List<PushRecord> records = pushRecordRepository.findByConfigIdOrderByPushTimeDesc(configId);
        List<PushResultDTO> result = new ArrayList<>();
        for (PushRecord r : records) {
            result.add(EntityConverter.toPushResultDTO(r));
        }
        return result;
    }

    public List<PushResultDTO> getPushRecordsByGroup(String groupId) {
        List<PushRecord> records = pushRecordRepository.findByTargetGroupOrderByPushTimeDesc(groupId);
        List<PushResultDTO> result = new ArrayList<>();
        for (PushRecord r : records) {
            result.add(EntityConverter.toPushResultDTO(r));
        }
        return result;
    }

    @Transactional
    public PushResultDTO retryPush(String pushId) {
        log.info("Retrying push: pushId={}", pushId);
        PushRecord record = pushRecordRepository.findById(pushId)
                .orElseThrow(() -> new BusinessException("推送记录不存在: " + pushId));

        if (record.getRetryCount() >= pushProperties.getMaxRetryCount()) {
            throw new BusinessException("已达到最大重试次数: " + pushProperties.getMaxRetryCount());
        }

        record.setRetryCount(record.getRetryCount() + 1);
        record.setPushStatus(PushStatus.PENDING);
        record.setPushTime(LocalDateTime.now());
        pushRecordRepository.save(record);

        return pushConfig(record.getConfigId(), record.getVersion(), record.getTargetGroup(), record.getPushBy());
    }
    
    @lombok.Builder
    @lombok.Data
    public static class PushTaskContext {
        private String pushId;
        private String configId;
        private String version;
        private String groupId;
        private List<ApplicationInstance> instances;
        private int parallelism;
    }
    
    @lombok.Builder
    @lombok.Data
    public static class PushTaskStatus {
        private String pushId;
        private PushStatus status;
        private LocalDateTime startTime;
        private int totalCount;
    }
    
    @lombok.AllArgsConstructor
    @lombok.Data
    public static class PushResult {
        private String instanceId;
        private boolean success;
        private String errorMessage;
    }
}