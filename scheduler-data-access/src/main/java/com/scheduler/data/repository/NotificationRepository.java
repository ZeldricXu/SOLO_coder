package com.scheduler.data.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.scheduler.data.cache.CacheManager;
import com.scheduler.persistence.entity.Notification;
import com.scheduler.persistence.mapper.NotificationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class NotificationRepository {

    private final NotificationMapper notificationMapper;
    private final CacheManager cacheManager;

    private static final String CACHE_PREFIX = "notification:";
    private static final String PENDING_CACHE_KEY = "notifications:pending";

    public Notification create(Notification notification) {
        notificationMapper.insert(notification);
        cacheManager.set(CACHE_PREFIX + notification.getId(), notification, 3600);
        cacheManager.evict(PENDING_CACHE_KEY);
        return notification;
    }

    public Notification update(Notification notification) {
        notificationMapper.updateById(notification);
        cacheManager.set(CACHE_PREFIX + notification.getId(), notification, 3600);
        cacheManager.evict(PENDING_CACHE_KEY);
        return notification;
    }

    public Notification findById(String id) {
        Notification cached = cacheManager.get(CACHE_PREFIX + id, Notification.class);
        if (cached != null) {
            return cached;
        }

        Notification notification = notificationMapper.selectById(id);
        if (notification != null) {
            cacheManager.set(CACHE_PREFIX + id, notification, 3600);
        }
        return notification;
    }

    public List<Notification> findPendingNotifications() {
        List<Notification> cached = cacheManager.get(PENDING_CACHE_KEY, List.class);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }

        QueryWrapper<Notification> wrapper = new QueryWrapper<>();
        wrapper.in("status", "PENDING", "FAILED")
                .lt("retry_count", 3)
                .orderByAsc("created_at");

        List<Notification> notifications = notificationMapper.selectList(wrapper);
        if (notifications != null && !notifications.isEmpty()) {
            cacheManager.set(PENDING_CACHE_KEY, notifications, 60);
        }
        return notifications;
    }

    public List<Notification> findByStatus(String status) {
        QueryWrapper<Notification> wrapper = new QueryWrapper<>();
        wrapper.eq("status", status).orderByDesc("created_at");
        return notificationMapper.selectList(wrapper);
    }

    public List<Notification> findByTimeRange(LocalDateTime startTime, LocalDateTime endTime) {
        QueryWrapper<Notification> wrapper = new QueryWrapper<>();
        wrapper.between("created_at", startTime, endTime).orderByDesc("created_at");
        return notificationMapper.selectList(wrapper);
    }
}
