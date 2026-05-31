package com.dynamiclog.notification.service;

import com.dynamiclog.common.entity.Notification;
import com.dynamiclog.common.enums.NotificationStatus;
import com.dynamiclog.common.enums.NotificationType;
import com.dynamiclog.common.exception.ResourceNotFoundException;
import com.dynamiclog.common.util.IdGenerator;
import com.dynamiclog.persistence.mapper.NotificationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationMapper notificationMapper;
    private final JavaMailSender mailSender;
    private final WebClient.Builder webClientBuilder;

    private final Map<String, Integer> retryBackoff = new ConcurrentHashMap<>();

    public Mono<Notification> sendNotification(
            NotificationType type,
            String recipient,
            String subject,
            String content,
            Map<String, Object> variables,
            String traceId) {
        return Mono.fromCallable(() -> {
            Notification notification = new Notification();
            notification.setId(IdGenerator.generateId("notif"));
            notification.setType(type);
            notification.setStatus(NotificationStatus.PENDING);
            notification.setRecipient(recipient);
            notification.setSubject(subject);
            notification.setContent(content);
            notification.setVariables(variables);
            notification.setPriority(0);
            notification.setMaxRetries(3);
            notification.setRetryCount(0);
            notification.setTraceId(traceId);
            notification.setTtlSeconds(86400L);
            notification.setExpiresAt(LocalDateTime.now().plusSeconds(86400));

            notificationMapper.insert(notification);
            return notification;
        }).flatMap(this::sendNotificationInternal);
    }

    private Mono<Notification> sendNotificationInternal(Notification notification) {
        return Mono.fromCallable(() -> {
            notification.setStatus(NotificationStatus.SENDING);
            notificationMapper.updateById(notification);
            return notification;
        }).flatMap(n -> {
            try {
                return doSend(n)
                        .doOnSuccess(receipt -> {
                            n.setStatus(NotificationStatus.DELIVERED);
                            n.setSentAt(LocalDateTime.now());
                            n.setDeliveredAt(LocalDateTime.now());
                            n.setDeliveryReceipt(receipt);
                            notificationMapper.updateById(n);
                            log.info("Notification delivered: id={}, type={}, recipient={}",
                                    n.getId(), n.getType(), n.getRecipient());
                        })
                        .thenReturn(n);
            } catch (Exception e) {
                return handleSendFailure(n, e).thenReturn(n);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<String> doSend(Notification notification) {
        return switch (notification.getType()) {
            case EMAIL -> sendEmail(notification);
            case SMS -> sendSms(notification);
            case WEBHOOK -> sendWebhook(notification);
            case IN_APP -> sendInApp(notification);
        };
    }

    private Mono<String> sendEmail(Notification notification) {
        return Mono.fromCallable(() -> {
            try {
                log.info("Sending email to: {}, subject: {}", notification.getRecipient(), notification.getSubject());
                return "email_receipt_" + IdGenerator.generateId("rcpt");
            } catch (Exception e) {
                throw new RuntimeException("Failed to send email: " + e.getMessage(), e);
            }
        });
    }

    private Mono<String> sendSms(Notification notification) {
        return Mono.fromCallable(() -> {
            log.info("Sending SMS to: {}", notification.getRecipient());
            return "sms_receipt_" + IdGenerator.generateId("rcpt");
        });
    }

    private Mono<String> sendWebhook(Notification notification) {
        return webClientBuilder.build()
                .post()
                .uri(notification.getRecipient())
                .bodyValue(notification.getContent())
                .retrieve()
                .bodyToMono(String.class)
                .map(response -> "webhook_receipt_" + IdGenerator.generateId("rcpt"));
    }

    private Mono<String> sendInApp(Notification notification) {
        return Mono.fromCallable(() -> {
            log.info("Sending in-app notification to user: {}", notification.getRecipient());
            return "inapp_receipt_" + IdGenerator.generateId("rcpt");
        });
    }

    private Mono<Void> handleSendFailure(Notification notification, Exception e) {
        return Mono.fromRunnable(() -> {
            notification.setRetryCount(notification.getRetryCount() + 1);
            notification.setErrorMessage(e.getMessage());

            if (notification.getRetryCount() >= notification.getMaxRetries()) {
                notification.setStatus(NotificationStatus.FAILED);
                log.error("Notification failed after max retries: id={}, error={}", notification.getId(), e.getMessage());
            } else {
                notification.setStatus(NotificationStatus.RETRYING);
                int backoffSeconds = (int) Math.pow(2, notification.getRetryCount()) * 5;
                retryBackoff.put(notification.getId(), backoffSeconds);
                log.warn("Notification will be retried: id={}, retryCount={}, backoff={}s",
                        notification.getId(), notification.getRetryCount(), backoffSeconds);
            }

            notificationMapper.updateById(notification);
        });
    }

    @Scheduled(fixedDelay = 10000)
    public void retryPendingNotifications() {
        List<Notification> retrying = notificationMapper.findByStatus(NotificationStatus.RETRYING);
        List<Notification> pending = notificationMapper.findByStatus(NotificationStatus.PENDING);

        Flux.fromIterable(retrying)
                .concatWithValues(pending.toArray(new Notification[0]))
                .filter(n -> !isExpired(n))
                .filter(n -> {
                    Integer backoff = retryBackoff.get(n.getId());
                    if (backoff != null && n.getUpdatedAt() != null) {
                        long secondsSinceUpdate = java.time.Duration.between(n.getUpdatedAt(), LocalDateTime.now()).getSeconds();
                        return secondsSinceUpdate >= backoff;
                    }
                    return true;
                })
                .flatMap(this::sendNotificationInternal, 3)
                .subscribe();
    }

    @Scheduled(fixedDelay = 3600000)
    public void expireOldNotifications() {
        List<Notification> pending = notificationMapper.findByStatus(NotificationStatus.PENDING);
        List<Notification> retrying = notificationMapper.findByStatus(NotificationStatus.RETRYING);

        Flux.fromIterable(pending)
                .concatWithValues(retrying.toArray(new Notification[0]))
                .filter(this::isExpired)
                .doOnNext(n -> {
                    n.setStatus(NotificationStatus.EXPIRED);
                    notificationMapper.updateById(n);
                    log.info("Notification expired: id={}", n.getId());
                })
                .subscribe();
    }

    public Mono<Notification> getNotification(String id) {
        return Mono.fromCallable(() -> {
            Notification notification = notificationMapper.selectById(id);
            if (notification == null) {
                throw new ResourceNotFoundException("Notification", id);
            }
            return notification;
        });
    }

    public Flux<Notification> getNotificationsByTraceId(String traceId) {
        return Mono.fromCallable(() -> notificationMapper.findByTraceId(traceId))
                .flatMapMany(Flux::fromIterable);
    }

    public Mono<NotificationStatus> getNotificationStatus(String id) {
        return getNotification(id)
                .map(Notification::getStatus);
    }

    private boolean isExpired(Notification notification) {
        return notification.getExpiresAt() != null && LocalDateTime.now().isAfter(notification.getExpiresAt());
    }

    public Mono<Map<String, Object>> getStats() {
        return Mono.fromCallable(() -> Map.of(
                "pending", notificationMapper.findByStatus(NotificationStatus.PENDING).size(),
                "retrying", notificationMapper.findByStatus(NotificationStatus.RETRYING).size(),
                "delivered", notificationMapper.findByStatus(NotificationStatus.DELIVERED).size(),
                "failed", notificationMapper.findByStatus(NotificationStatus.FAILED).size(),
                "expired", notificationMapper.findByStatus(NotificationStatus.EXPIRED).size()
        ));
    }
}
