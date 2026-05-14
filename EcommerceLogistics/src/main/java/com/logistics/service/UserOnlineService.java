package com.logistics.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class UserOnlineService {

    private final Map<String, Long> onlineUsers = new ConcurrentHashMap<>();
    private static final long ONLINE_TIMEOUT_MS = 300000;

    public boolean isUserOnline(String userId) {
        if (userId == null) {
            return false;
        }
        
        Long lastActiveTime = onlineUsers.get(userId);
        if (lastActiveTime == null) {
            return false;
        }
        
        boolean isOnline = (System.currentTimeMillis() - lastActiveTime) < ONLINE_TIMEOUT_MS;
        if (!isOnline) {
            onlineUsers.remove(userId);
        }
        return isOnline;
    }

    public void markUserOnline(String userId) {
        onlineUsers.put(userId, System.currentTimeMillis());
        log.debug("用户 {} 已标记为在线", userId);
    }

    public void markUserOffline(String userId) {
        onlineUsers.remove(userId);
        log.debug("用户 {} 已标记为离线", userId);
    }

    public void updateUserActivity(String userId) {
        if (onlineUsers.containsKey(userId)) {
            onlineUsers.put(userId, System.currentTimeMillis());
        }
    }

    public int getOnlineUserCount() {
        cleanupExpiredUsers();
        return onlineUsers.size();
    }

    private void cleanupExpiredUsers() {
        long threshold = System.currentTimeMillis() - ONLINE_TIMEOUT_MS;
        onlineUsers.entrySet().removeIf(entry -> entry.getValue() < threshold);
    }
}
