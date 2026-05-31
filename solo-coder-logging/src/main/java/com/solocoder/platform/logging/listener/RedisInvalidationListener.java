package com.solocoder.platform.logging.listener;

import com.solocoder.platform.logging.cache.MultiLevelCacheManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisInvalidationListener implements MessageListener {

    private static final String INVALIDATION_CHANNEL = "log:level:invalidate";

    private final MultiLevelCacheManager cacheManager;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel());
        String loggerName = new String(message.getBody());

        if (INVALIDATION_CHANNEL.equals(channel)) {
            log.info("Redis invalidation message received: logger={}", loggerName);
            cacheManager.onRemoteInvalidation(loggerName);
        }
    }
}
