package com.datastandard.modules.notification;

import cn.hutool.core.util.StrUtil;
import com.datastandard.modules.notification.dto.NotificationRequest;
import com.datastandard.modules.notification.dto.NotificationResult;
import com.datastandard.modules.notification.dto.TemplateDefinition;
import com.datastandard.modules.notification.entity.NotificationRecord;
import com.datastandard.modules.notification.mapper.NotificationRecordMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final ApplicationContext applicationContext;
    private final List<NotificationChannel> channels;
    private final TemplateEngine templateEngine;
    private final NotificationRecordMapper recordMapper;
    private NotificationRetryHandler retryHandler;

    private final PriorityBlockingQueue<NotificationTask> taskQueue =
            new PriorityBlockingQueue<>(1000, (a, b) -> Integer.compare(b.priority, a.priority));

    private final ScheduledExecutorService executorService = new ScheduledThreadPoolExecutor(4);
    private final AtomicBoolean running = new AtomicBoolean(false);

    @PostConstruct
    public void init() {
        retryHandler = applicationContext.getBean(NotificationRetryHandler.class);
        retryHandler.start();
        startWorkerThreads();
        log.info("NotificationService initialized with {} channels: {}",
                channels.size(),
                channels.stream().map(NotificationChannel::getChannelName).toList());
    }

    private void startWorkerThreads() {
        if (running.compareAndSet(false, true)) {
            for (int i = 0; i < 4; i++) {
                executorService.submit(this::processQueue);
            }
            log.info("Started 4 notification worker threads");
        }
    }

    private void processQueue() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                NotificationTask task = taskQueue.poll(5, TimeUnit.SECONDS);
                if (task != null) {
                    processTask(task).subscribe();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error processing notification task", e);
            }
        }
    }

    public Flux<NotificationResult> send(NotificationRequest request) {
        if (request.getTemplateParams() == null) {
            request.setTemplateParams(new HashMap<>());
        }

        if (StrUtil.isBlank(request.getSubject()) && StrUtil.isNotBlank(request.getTemplateCode())) {
            request.setSubject(templateEngine.renderSubject(request.getTemplateCode(), request.getTemplateParams()));
        }
        if (StrUtil.isBlank(request.getContent()) && StrUtil.isNotBlank(request.getTemplateCode())) {
            request.setContent(templateEngine.renderContent(request.getTemplateCode(), request.getTemplateParams()));
        }

        List<NotificationResult> results = new ArrayList<>();

        for (String recipient : request.getRecipients()) {
            NotificationRecord record = createRecord(request, recipient);
            recordMapper.insert(record);

            List<NotificationChannel> availableChannels = findAvailableChannels(request);

            if (request.isAsync()) {
                NotificationTask task = new NotificationTask(request, recipient, record, availableChannels);
                taskQueue.offer(task);
                results.add(NotificationResult.builder()
                        .recordId(record.getRecordId())
                        .channel("QUEUED")
                        .recipient(recipient)
                        .success(true)
                        .traceId(request.getTraceId())
                        .build());
            } else {
                NotificationResult result = sendToChannels(request, recipient, record, availableChannels).block();
                if (result != null) {
                    results.add(result);
                }
            }
        }

        return Flux.fromIterable(results);
    }

    private Mono<NotificationResult> processTask(NotificationTask task) {
        return sendToChannels(task.request, task.recipient, task.record, task.channels)
                .doOnNext(result -> {
                    if (!result.isSuccess() && task.record.getRetryCount() < retryHandler.getMaxRetries()) {
                        task.record.setRetryCount(task.record.getRetryCount() + 1);
                        task.record.setErrorMessage(result.getErrorMessage());
                        task.record.setStatus("RETRYING");
                        recordMapper.updateById(task.record);
                        retryHandler.scheduleRetry(task.record);
                    }
                })
                .onErrorResume(e -> {
                    log.error("Failed to process notification task: {}", e.getMessage(), e);
                    return Mono.just(NotificationResult.failure(
                            "UNKNOWN", task.recipient, e.getMessage(), 0));
                });
    }

    private Mono<NotificationResult> sendToChannels(NotificationRequest request, String recipient,
                                                     NotificationRecord record,
                                                     List<NotificationChannel> availableChannels) {
        return Flux.fromIterable(availableChannels)
                .filter(channel -> channel.supports(request))
                .next()
                .flatMap(channel -> {
                    record.setChannel(channel.getChannelName());
                    record.setStatus("SENDING");
                    recordMapper.updateById(record);

                    return channel.send(request, recipient)
                            .doOnNext(result -> {
                                result.setRecordId(record.getRecordId());
                                result.setTraceId(request.getTraceId());
                                updateRecordStatus(record, result);
                            });
                })
                .switchIfEmpty(Mono.fromCallable(() -> {
                    String error = "No available channel supports this notification";
                    record.setStatus("FAILED");
                    record.setErrorMessage(error);
                    recordMapper.updateById(record);
                    return NotificationResult.failure("NONE", recipient, error, request.getRetryCount());
                }));
    }

    private void updateRecordStatus(NotificationRecord record, NotificationResult result) {
        if (result.isSuccess()) {
            record.setStatus("SENT");
            record.setSentAt(Instant.now());
            record.setDurationMs(result.getDurationMs());
        } else {
            record.setStatus("FAILED");
            record.setErrorMessage(result.getErrorMessage());
        }
        recordMapper.updateById(record);
    }

    private List<NotificationChannel> findAvailableChannels(NotificationRequest request) {
        TemplateDefinition template = templateEngine.getTemplate(request.getTemplateCode());
        Set<String> supportedChannels = template != null ? template.getSupportedChannels() : null;

        return channels.stream()
                .filter(NotificationChannel::isAvailable)
                .filter(ch -> supportedChannels == null || supportedChannels.contains(ch.getChannelName()))
                .sorted(Comparator.comparingInt(NotificationChannel::getPriority))
                .toList();
    }

    private NotificationRecord createRecord(NotificationRequest request, String recipient) {
        return NotificationRecord.builder()
                .traceId(request.getTraceId())
                .type(request.getType())
                .recipient(recipient)
                .sender(request.getSender())
                .templateCode(request.getTemplateCode())
                .subject(request.getSubject())
                .content(request.getContent())
                .status("PENDING")
                .priority(request.getPriority())
                .retryCount(0)
                .maxRetries(retryHandler.getMaxRetries())
                .scheduledTime(request.getScheduledTime())
                .createdAt(Instant.now())
                .deleted(0)
                .build();
    }

    public Mono<Void> registerTemplate(TemplateDefinition template) {
        return Mono.fromRunnable(() -> templateEngine.registerTemplate(template))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    public Mono<Long> getQueuedTaskCount() {
        return Mono.fromCallable(() -> (long) taskQueue.size());
    }

    public void shutdown() {
        if (running.compareAndSet(true, false)) {
            retryHandler.stop();
            executorService.shutdown();
            log.info("NotificationService shutdown complete");
        }
    }

    private static class NotificationTask {
        final NotificationRequest request;
        final String recipient;
        final NotificationRecord record;
        final List<NotificationChannel> channels;
        final int priority;
        final long createdAt;

        NotificationTask(NotificationRequest request, String recipient,
                         NotificationRecord record, List<NotificationChannel> channels) {
            this.request = request;
            this.recipient = recipient;
            this.record = record;
            this.channels = channels;
            this.priority = request.getPriority();
            this.createdAt = System.currentTimeMillis();
        }
    }
}
