package com.datastandard.modules.notification;

import com.datastandard.modules.notification.dto.NotificationRequest;
import com.datastandard.modules.notification.entity.NotificationRecord;
import com.datastandard.modules.notification.mapper.NotificationRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationRetryHandler {

    private final ApplicationContext applicationContext;
    private final NotificationRecordMapper recordMapper;
    private NotificationService notificationService;

    @Value("${notification.retry.max-retries:3}")
    private int maxRetries;

    @Value("${notification.retry.delay-seconds:10}")
    private int retryDelaySeconds;

    @Value("${notification.retry.backoff-multiplier:2}")
    private int backoffMultiplier;

    @Value("${notification.retry.batch-size:100}")
    private int batchSize;

    @Value("${notification.retry.enabled:true}")
    private boolean enabled;

    private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(1);
    private final AtomicBoolean running = new AtomicBoolean(false);

    public void start() {
        if (!enabled || !running.compareAndSet(false, true)) {
            return;
        }
        notificationService = applicationContext.getBean(NotificationService.class);
        scheduler.scheduleAtFixedRate(this::processRetryQueue, retryDelaySeconds, retryDelaySeconds, TimeUnit.SECONDS);
        log.info("Notification retry handler started with delay {} seconds", retryDelaySeconds);
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            scheduler.shutdown();
            log.info("Notification retry handler stopped");
        }
    }

    public void scheduleRetry(NotificationRecord record) {
        if (!enabled) {
            return;
        }
        if (record.getRetryCount() >= maxRetries) {
            log.warn("Notification {} reached max retries ({})", record.getRecordId(), maxRetries);
            record.setStatus("FAILED_PERMANENT");
            recordMapper.updateById(record);
            return;
        }

        long delay = calculateDelay(record.getRetryCount());
        scheduler.schedule(() -> retryNotification(record), delay, TimeUnit.MILLISECONDS);
        log.debug("Scheduled retry for notification {} in {} ms", record.getRecordId(), delay);
    }

    public long calculateDelay(int retryCount) {
        return (long) (retryDelaySeconds * 1000 * Math.pow(backoffMultiplier, retryCount));
    }

    private void processRetryQueue() {
        if (!enabled) {
            return;
        }
        try {
            Flux.fromIterable(recordMapper.findRetryableRecords(batchSize))
                    .flatMap(this::retryNotification)
                    .subscribeOn(Schedulers.boundedElastic())
                    .collectList()
                    .doOnSuccess(results -> {
                        if (!results.isEmpty()) {
                            log.debug("Processed {} retry notifications", results.size());
                        }
                    })
                    .doOnError(e -> log.error("Error processing retry queue", e))
                    .subscribe();
        } catch (Exception e) {
            log.error("Error in retry queue processing", e);
        }
    }

    private Mono<Void> retryNotification(NotificationRecord record) {
        return Mono.fromCallable(() -> {
            try {
                log.debug("Retrying notification {}, attempt {}", record.getRecordId(), record.getRetryCount() + 1);

                NotificationRequest request = NotificationRequest.builder()
                        .type(record.getType())
                        .recipients(java.util.Collections.singletonList(record.getRecipient()))
                        .templateCode(record.getTemplateCode())
                        .subject(record.getSubject())
                        .content(record.getContent())
                        .priority(record.getPriority())
                        .sender(record.getSender())
                        .traceId(record.getTraceId())
                        .retryCount(record.getRetryCount() + 1)
                        .async(false)
                        .build();

                notificationService.send(request)
                        .doOnNext(result -> {
                            if (result.isSuccess()) {
                                record.setStatus("SENT");
                                record.setSentAt(Instant.now());
                                log.info("Notification {} retry succeeded", record.getRecordId());
                            } else {
                                record.setRetryCount(record.getRetryCount() + 1);
                                record.setErrorMessage(result.getErrorMessage());
                                if (record.getRetryCount() >= maxRetries) {
                                    record.setStatus("FAILED_PERMANENT");
                                    log.warn("Notification {} retry failed permanently", record.getRecordId());
                                } else {
                                    record.setStatus("RETRYING");
                                }
                            }
                            recordMapper.updateById(record);
                        })
                        .onErrorResume(e -> {
                            record.setRetryCount(record.getRetryCount() + 1);
                            record.setErrorMessage(e.getMessage());
                            if (record.getRetryCount() >= maxRetries) {
                                record.setStatus("FAILED_PERMANENT");
                            }
                            recordMapper.updateById(record);
                            log.error("Notification {} retry failed: {}", record.getRecordId(), e.getMessage());
                            return Mono.empty();
                        })
                        .block(Duration.ofSeconds(30));

                return null;
            } catch (Exception e) {
                log.error("Error retrying notification {}: {}", record.getRecordId(), e.getMessage());
                return null;
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public boolean isRunning() {
        return running.get();
    }
}
