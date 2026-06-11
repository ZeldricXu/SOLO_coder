package com.exam.monitor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class MonitorWebSocketThrottler {

    private static final long MIN_SEND_INTERVAL_MS = 500;
    private static final int MAX_MESSAGES_PER_SECOND = 2;

    private final ConcurrentHashMap<String, SessionThrottle> sessions = new ConcurrentHashMap<>();

    public boolean trySend(WebSocketSession session, String message) {
        if (session == null || !session.isOpen()) {
            return false;
        }
        String sessionId = session.getId();
        SessionThrottle throttle = sessions.computeIfAbsent(sessionId, k -> new SessionThrottle());

        boolean allowed = throttle.tryAcquire();
        if (!allowed) {
            log.debug("WebSocket推送节流，sessionId={}，丢弃消息", sessionId);
            return false;
        }

        try {
            session.sendMessage(new TextMessage(message));
            return true;
        } catch (Exception e) {
            log.warn("WebSocket推送失败: {}", e.getMessage());
            removeSession(sessionId);
            return false;
        }
    }

    public void removeSession(String sessionId) {
        sessions.remove(sessionId);
    }

    public int getActiveSessionCount() {
        return sessions.size();
    }

    private static class SessionThrottle {
        private final AtomicLong lastSendTime = new AtomicLong(0);
        private volatile long windowStart = 0;
        private volatile int windowCount = 0;
        private final Object lock = new Object();

        boolean tryAcquire() {
            long now = System.currentTimeMillis();

            synchronized (lock) {
                if (now - windowStart >= 1000) {
                    windowStart = now;
                    windowCount = 0;
                }

                if (windowCount >= MAX_MESSAGES_PER_SECOND) {
                    return false;
                }

                long last = lastSendTime.get();
                if (now - last < MIN_SEND_INTERVAL_MS) {
                    return false;
                }

                windowCount++;
                lastSendTime.set(now);
                return true;
            }
        }
    }
}
