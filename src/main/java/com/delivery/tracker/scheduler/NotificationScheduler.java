package com.delivery.tracker.scheduler;

import com.delivery.tracker.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationScheduler {

    private final NotificationService notificationService;

    @Scheduled(fixedRate = 60000)
    public void processPendingNotifications() {
        log.debug("开始处理待发送通知");
        notificationService.processPendingNotifications()
                .doOnComplete(() -> log.debug("待发送通知处理完成"))
                .doOnError(e -> log.error("处理待发送通知失败", e))
                .subscribe();
    }
}
