package com.solocoder.platform.notification.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class NotificationConfigWatcher {

    private final NotificationConfigManager configManager;
    private final AtomicLong lastModifiedTime = new AtomicLong(0);

    @Scheduled(fixedDelayString = "${notification.config.refresh-interval:5000}")
    public void checkConfigChanges() {
        try {
            String configPath = configManager.getCurrentConfig() != null ? "./config/notification-config.json" : null;
            if (configPath == null) return;

            Path path = Paths.get(configPath);
            if (!Files.exists(path)) return;

            long modified = Files.getLastModifiedTime(path).toMillis();
            long previous = lastModifiedTime.get();
            if (modified > previous) {
                lastModifiedTime.set(modified);
                configManager.reload();
                log.info("Notification config file changed, triggered hot-reload: version={}", configManager.getConfigVersion());
            }
        } catch (Exception e) {
            log.debug("Config file watch check failed", e);
        }
    }

    public void setLastModifiedTime(long time) {
        lastModifiedTime.set(time);
    }
}
