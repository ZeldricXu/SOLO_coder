package com.configcenter.push.service;

import com.alibaba.fastjson.JSON;
import com.configcenter.common.entity.ApplicationInstance;
import com.configcenter.common.entity.ConfigGroup;
import com.configcenter.common.enums.InstanceStatus;
import com.configcenter.common.enums.PushStatus;
import com.configcenter.common.enums.PushStrategy;
import com.configcenter.common.exception.BusinessException;
import com.configcenter.common.util.EntityConverter;
import com.configcenter.common.dto.PushResultDTO;
import com.configcenter.common.entity.PushRecord;
import com.configcenter.group.repository.ApplicationInstanceRepository;
import com.configcenter.group.repository.ConfigGroupRepository;
import com.configcenter.push.config.PushProperties;
import com.configcenter.push.event.PushTaskEvent;
import com.configcenter.push.repository.PushRecordRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncPushService {

    private final PushRecordRepository pushRecordRepository;
    private final ConfigGroupRepository configGroupRepository;
    private final ApplicationInstanceRepository instanceRepository;
    private final PushProperties pushProperties;
    private final ApplicationEventPublisher eventPublisher;

    @Getter
    private final BlockingQueue<PushTaskEvent> pushTaskQueue = new LinkedBlockingQueue<>();

    private volatile boolean running = true;

    private ThreadPoolExecutor createExecutorForParallelism(int parallelism) {
        int actualParallelism = Math.max(1, parallelism);
        return new ThreadPoolExecutor(
                actualParallelism,
                actualParallelism * 2,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        log.info("AsyncPushService started");
    }

    public void stop() {
        running = false;
        log.info("AsyncPushService stopped");
    }

    public int getQueueSize() {
        return pushTaskQueue.size();
    }

    @Transactional
    public PushResultDTO submitPushTask(String configId, String version, String groupId, String pushBy) {
        log.info("Submitting async push task: configId={}, version={}, groupId={}", configId, version, groupId);

        ConfigGroup group = configGroupRepository.findByGroupIdAndDeletedFalse(groupId)
                .orElseThrow(() -> new BusinessException("分组不存在: " + groupId));

        List<String> applications = group.getApplications();
        List<ApplicationInstance> instances = new ArrayList<>();
        if (applications != null && !applications.isEmpty()) {
            instances = instanceRepository.findByApplicationsAndStatus(applications, InstanceStatus.ONLINE);
        }

        int parallelism = determineParallelism(group, instances);

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

        Map<String, Object> pushMessage = new HashMap<>();
        pushMessage.put("configId", configId);
        pushMessage.put("version", version);
        pushMessage.put("pushTime", LocalDateTime.now().toString());
        pushMessage.put("parallelism", parallelism);

        PushTaskEvent event = PushTaskEvent.builder()
                .pushId(saved.getPushId())
                .configId(configId)
                .version(version)
                .groupId(groupId)
                .pushBy(pushBy)
                .pushMessage(JSON.toJSONString(pushMessage))
                .createdAt(LocalDateTime.now())
                .parallelism(parallelism)
                .build();

        if (pushProperties.getAsync().getEnabled()) {
            boolean queued = pushTaskQueue.offer(event);
            if (!queued) {
                log.warn("Push task queue full, executing synchronously");
                executePushTask(event);
            } else {
                log.info("Push task queued: pushId={}, queueSize={}", saved.getPushId(), pushTaskQueue.size());
            }
            saved.setPushStatus(PushStatus.PUSHING);
            pushRecordRepository.save(saved);
        } else {
            log.info("Async push disabled, executing synchronously");
            executePushTask(event);
        }

        return EntityConverter.toPushResultDTO(saved);
    }

    private int determineParallelism(ConfigGroup group, List<ApplicationInstance> instances) {
        if (group.getParallelPushCount() != null && group.getParallelPushCount() > 0) {
            return group.getParallelPushCount();
        }

        if (group.getGroupId() != null) {
            Integer configured = pushProperties.getParallelismForGroup(group.getGroupId());
            if (configured != null && configured > 0) {
                return configured;
            }
        }

        int instanceCount = instances.size();
        if (instanceCount >= 50) {
            return Math.min(pushProperties.getParallelPushCount() * 3, 50);
        } else if (instanceCount >= 20) {
            return Math.min(pushProperties.getParallelPushCount() * 2, 30);
        } else if (instanceCount >= 10) {
            return pushProperties.getParallelPushCount();
        } else {
            return Math.max(2, Math.min(instanceCount, 5));
        }
    }

    @Async
    public void processQueue() {
        while (running) {
            try {
                PushTaskEvent event = pushTaskQueue.poll(1, TimeUnit.SECONDS);
                if (event != null) {
                    log.info("Processing push task from queue: pushId={}", event.getPushId());
                    executePushTask(event);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Push queue processing interrupted");
                break;
            } catch (Exception e) {
                log.error("Error processing push task from queue", e);
            }
        }
    }

    @Transactional
    public void executePushTask(PushTaskEvent event) {
        log.info("Executing push task: pushId={}, retry={}", event.getPushId(), event.getRetryCount());

        PushRecord record = pushRecordRepository.findById(event.getPushId()).orElse(null);
        if (record == null) {
            log.warn("Push record not found: pushId={}", event.getPushId());
            return;
        }

        try {
            ConfigGroup group = configGroupRepository.findByGroupIdAndDeletedFalse(event.getGroupId()).orElse(null);
            if (group == null) {
                log.warn("Group not found for push: groupId={}", event.getGroupId());
                record.setPushStatus(PushStatus.FAILED);
                record.setFailCount(record.getTotalCount());
                record.setCompleteTime(LocalDateTime.now());
                pushRecordRepository.save(record);
                return;
            }

            List<String> applications = group.getApplications();
            List<ApplicationInstance> instances = new ArrayList<>();
            if (applications != null && !applications.isEmpty()) {
                instances = instanceRepository.findByApplicationsAndStatus(applications, InstanceStatus.ONLINE);
            }

            record.setPushStatus(PushStatus.PUSHING);
            pushRecordRepository.save(record);

            if (instances.isEmpty()) {
                log.warn("No online instances, completing push: pushId={}", event.getPushId());
                record.setPushStatus(PushStatus.COMPLETED);
                record.setCompleteTime(LocalDateTime.now());
                pushRecordRepository.save(record);
                return;
            }

            Map<String, Object> pushMessage = new HashMap<>();
            pushMessage.put("configId", event.getConfigId());
            pushMessage.put("version", event.getVersion());
            pushMessage.put("pushTime", LocalDateTime.now().toString());
            String jsonMessage = JSON.toJSONString(pushMessage);

            int parallelism = determineParallelism(group, instances);
            log.info("Push parallelism determined: instances={}, parallelism={}", instances.size(), parallelism);

            doPushWithParallelism(record, instances, jsonMessage, parallelism);

        } catch (Exception e) {
            log.error("Push execution failed: pushId={}", event.getPushId(), e);
            
            if (event.canRetry()) {
                log.info("Retrying push task: pushId={}, retry={}/{}", 
                        event.getPushId(), event.getRetryCount() + 1, event.getMaxRetries());
                PushTaskEvent retryEvent = event.forRetry();
                pushTaskQueue.offer(retryEvent);
            } else {
                record.setPushStatus(PushStatus.FAILED);
                record.setCompleteTime(LocalDateTime.now());
                pushRecordRepository.save(record);
            }
        }
    }

    private void doPushWithParallelism(PushRecord record, List<ApplicationInstance> instances, 
                                        String jsonMessage, int parallelism) {
        log.info("Starting push with parallelism={}, instances={}", parallelism, instances.size());

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        List<Future<Boolean>> futures = new ArrayList<>();

        ThreadPoolExecutor executor = createExecutorForParallelism(parallelism);

        try {
            for (ApplicationInstance instance : instances) {
                Future<Boolean> future = executor.submit(() -> pushToInstance(instance, jsonMessage));
                futures.add(future);
            }

            for (Future<Boolean> future : futures) {
                try {
                    Boolean result = future.get(pushProperties.getPushTimeoutSeconds(), TimeUnit.SECONDS);
                    if (Boolean.TRUE.equals(result)) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.error("Push future execution error", e);
                    failCount.incrementAndGet();
                }
            }
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        record.setSuccessCount(successCount.get());
        record.setFailCount(failCount.get());
        record.setCompleteTime(LocalDateTime.now());

        if (failCount.get() == 0) {
            record.setPushStatus(PushStatus.COMPLETED);
        } else if (successCount.get() == 0) {
            record.setPushStatus(PushStatus.FAILED);
        } else {
            record.setPushStatus(PushStatus.PARTIAL_FAILED);
        }

        pushRecordRepository.save(record);
        log.info("Push completed: pushId={}, parallelism={}, success={}, fail={}", 
                record.getPushId(), parallelism, successCount.get(), failCount.get());
    }

    private boolean pushToInstance(ApplicationInstance instance, String jsonMessage) {
        String url = "http://" + instance.getInstanceAddress() + pushProperties.getPushEndpoint();
        try {
            cn.hutool.http.HttpResponse response = cn.hutool.http.HttpRequest.post(url)
                    .body(jsonMessage)
                    .contentType("application/json")
                    .timeout(pushProperties.getPushTimeoutSeconds() * 1000)
                    .execute();

            if (response.isOk()) {
                log.info("Push success to instance: {}", instance.getInstanceAddress());
                instance.setLastConfigSync(LocalDateTime.now());
                instanceRepository.save(instance);
                return true;
            } else {
                log.warn("Push failed to instance: {}, status: {}", 
                        instance.getInstanceAddress(), response.getStatus());
                return false;
            }
        } catch (Exception e) {
            log.error("Push exception to instance: {}", instance.getInstanceAddress(), e);
            return false;
        }
    }
}
