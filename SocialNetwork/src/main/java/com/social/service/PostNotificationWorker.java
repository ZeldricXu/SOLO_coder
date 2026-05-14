package com.social.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PostNotificationWorker {

    private static final Logger logger = LoggerFactory.getLogger(PostNotificationWorker.class);

    @Autowired
    private PostPushService postPushService;

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void processQueuedNotifications() {
        long queuedCount = postPushService.countQueuedNotifications();
        if (queuedCount > 0) {
            logger.info("开始处理排队的动态通知: {} 个", queuedCount);
            int processed = postPushService.processQueuedNotifications().size();
            logger.info("动态通知处理完成: 处理了 {} 个", processed);
        }
    }
}
