package com.delivery.tracker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.delivery.tracker.entity.Notification;
import com.delivery.tracker.mapper.NotificationMapper;
import com.delivery.tracker.notification.NotificationStrategy;
import com.delivery.tracker.notification.NotificationStrategyFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.LocalDateTime;

/**
 * 通知服务
 * 支持可插拔策略，运行时动态切换
 * 接口保持向后兼容
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationMapper notificationMapper;
    private final NotificationStrategyFactory strategyFactory;

    public Mono<Notification> createNotification(String type, String recipient, String content) {
        return Mono.fromCallable(() -> {
            Notification notification = new Notification();
            notification.setType(type);
            notification.setRecipient(recipient);
            notification.setContent(content);
            notification.setStatus("PENDING");
            notification.setRetryCount(0);
            notification.setCreatedAt(LocalDateTime.now());
            notification.setUpdatedAt(LocalDateTime.now());
            notificationMapper.insert(notification);
            return notification;
        });
    }

    public Mono<Notification> sendNotification(Notification notification) {
        return Mono.fromCallable(() -> {
            if (notification.getStatus().equals("SENT")) {
                return notification;
            }

            NotificationStrategy strategy = strategyFactory.getStrategy(notification.getType());
            NotificationStrategy.RetryConfig retryConfig = strategy.getRetryConfig();

            try {
                strategy.send(notification);
                notification.setStatus("SENT");
                notification.setSentAt(LocalDateTime.now());
                log.info("通知发送成功: id={}, type={}", notification.getId(), notification.getType());
            } catch (Exception e) {
                notification.setRetryCount(notification.getRetryCount() + 1);
                notification.setLastError(e.getMessage());

                if (notification.getRetryCount() >= retryConfig.getMaxRetries()) {
                    notification.setStatus("FAILED");
                    log.error("通知最终发送失败: id={}, type={}, attempts={}",
                            notification.getId(), notification.getType(), notification.getRetryCount());
                } else {
                    notification.setStatus("RETRYING");
                    notification.setNextRetryAt(LocalDateTime.now().plusMillis(
                            calculateBackoff(notification.getRetryCount(), retryConfig)
                    ));
                    log.warn("通知发送失败，等待重试: id={}, type={}, attempt={}, nextRetry={}",
                            notification.getId(), notification.getType(),
                            notification.getRetryCount(), notification.getNextRetryAt());
                }
            }

            notification.setUpdatedAt(LocalDateTime.now());
            notificationMapper.updateById(notification);
            return notification;
        });
    }

    public Flux<Notification> processPendingNotifications() {
        return Flux.defer(() -> {
            LocalDateTime now = LocalDateTime.now();
            java.util.List<Notification> pending = notificationMapper.selectList(
                    new LambdaQueryWrapper<Notification>()
                            .and(wrapper -> wrapper
                                    .eq(Notification::getStatus, "PENDING")
                                    .or()
                                    .eq(Notification::getStatus, "RETRYING")
                            )
                            .and(wrapper -> wrapper
                                    .isNull(Notification::getNextRetryAt)
                                    .or()
                                    .lt(Notification::getNextRetryAt, now)
                            )
            );
            return Flux.fromIterable(pending)
                    .flatMap(this::sendNotification);
        });
    }

    /**
     * 指数退避计算
     */
    private long calculateBackoff(int retryCount, NotificationStrategy.RetryConfig config) {
        long backoff = (long) (config.getInitialBackoffMs() * Math.pow(config.getBackoffMultiplier(), retryCount - 1));
        return Math.min(backoff, config.getMaxBackoffMs());
    }

    /**
     * 使用指定策略发送通知（新接口）
     */
    public Mono<Notification> sendNotificationWithStrategy(Notification notification, String strategyType) {
        if (!strategyFactory.hasStrategy(strategyType)) {
            return Mono.error(new IllegalArgumentException("不支持的通知策略: " + strategyType));
        }

        notification.setType(strategyType);
        return sendNotification(notification);
    }

    /**
     * 获取支持的策略列表（新接口）
     */
    public Mono<java.util.List<String>> getAvailableStrategies() {
        return Mono.just(strategyFactory.getAllStrategyNames());
    }

    /**
     * 动态注册策略（新接口）
     */
    public Mono<Void> registerStrategy(NotificationStrategy strategy) {
        return Mono.fromRunnable(() -> strategyFactory.registerStrategy(strategy));
    }

    /**
     * 动态移除策略（新接口）
     */
    public Mono<Void> unregisterStrategy(String type) {
        return Mono.fromRunnable(() -> strategyFactory.unregisterStrategy(type));
    }

    public Mono<Notification> getNotificationById(Long id) {
        return Mono.fromCallable(() -> notificationMapper.selectById(id));
    }

    public Mono<Notification> getNotificationStatus(Long id) {
        return getNotificationById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("通知不存在: " + id)));
    }

    public Flux<Notification> getNotificationsByStatus(String status) {
        return Flux.defer(() -> {
            java.util.List<Notification> notifications = notificationMapper.selectList(
                    new LambdaQueryWrapper<Notification>()
                            .eq(Notification::getStatus, status)
                            .orderByDesc(Notification::getCreatedAt)
            );
            return Flux.fromIterable(notifications);
        });
    }
}
