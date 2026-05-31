package com.solocoder.platform.logging.listener;

import com.solocoder.platform.logging.cache.MultiLevelCacheManager;
import com.solocoder.platform.logging.event.LogLevelChangeEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LogLevelChangeListener {

    private final MultiLevelCacheManager cacheManager;

    @EventListener
    public void onLogLevelChange(LogLevelChangeEvent event) {
        log.info("Log level change event received: logger={}, oldLevel={}, newLevel={}, source={}, nodeId={}",
                event.getLoggerName(), event.getOldLevel(), event.getNewLevel(), event.getSource(), event.getNodeId());

        if (!cacheManager.getL1Cache().getNodeId().equals(event.getNodeId())) {
            cacheManager.onRemoteInvalidation(event.getLoggerName());
            log.info("Remote invalidation applied for logger={}", event.getLoggerName());
        }
    }
}
