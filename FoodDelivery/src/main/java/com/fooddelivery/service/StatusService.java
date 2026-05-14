package com.fooddelivery.service;

import com.fooddelivery.config.PushConfigProperties;
import com.fooddelivery.entity.Notify;
import com.fooddelivery.entity.Track;
import com.fooddelivery.repository.NotifyRepository;
import com.fooddelivery.repository.TrackRepository;
import com.fooddelivery.util.IdGenerator;
import com.fooddelivery.util.NotificationPushService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
public class StatusService {

    @Autowired
    private NotifyRepository notifyRepository;

    @Autowired
    private TrackRepository trackRepository;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private NotificationPushService notificationPushService;

    @Autowired
    private PushConfigProperties pushConfig;

    @Transactional
    public Notify createNotify(String orderId, String type, String status, String message) {
        return createNotify(orderId, type, status, message, "user_default");
    }

    @Transactional
    public Notify createNotify(String orderId, String type, String status, String message, String userId) {
        Notify notify = new Notify();
        notify.setNotifyId(IdGenerator.generateNotifyId());
        notify.setOrderId(orderId);
        notify.setNotifyType(type);
        notify.setNotifyStatus(status);
        notify.setNotifyMessage(message);
        notify.setIsRead(false);
        Notify saved = notifyRepository.save(notify);
        historyService.recordHistory("notify", saved.getNotifyId(), "create",
                "创建通知：" + message);

        pushStatusUpdate(orderId, status, message, userId);

        return saved;
    }

    public void pushStatusUpdate(String orderId, String status, String message, String userId) {
        boolean pushed = notificationPushService.pushNotification(userId, orderId, status, message);
        if (pushed) {
            log.debug("状态推送成功: orderId={}, status={}, userId={}", orderId, status, userId);
        } else {
            log.debug("状态推送存储为离线消息: orderId={}, status={}, userId={}", orderId, status, userId);
        }
    }

    public List<Notify> getNotificationsByOrderId(String orderId) {
        return notifyRepository.findByOrderIdOrderByNotifyTimeDesc(orderId);
    }

    @Transactional
    public Notify markNotificationAsRead(String notifyId) {
        Notify notify = notifyRepository.findById(notifyId)
                .orElseThrow(() -> new RuntimeException("通知不存在"));
        notify.setIsRead(true);
        return notifyRepository.save(notify);
    }

    @Transactional
    public Track createTrack(String deliveryId, String status, String location) {
        Track track = new Track();
        track.setTrackId(IdGenerator.generateTrackId());
        track.setDeliveryId(deliveryId);
        track.setTrackStatus(status);
        track.setTrackLocation(location);
        Track saved = trackRepository.save(track);
        historyService.recordHistory("track", saved.getTrackId(), "create",
                "记录轨迹：状态=" + status + ", 位置=" + location);
        return saved;
    }

    public List<Track> getTracksByDeliveryId(String deliveryId) {
        return trackRepository.findByDeliveryIdOrderByTrackTimeAsc(deliveryId);
    }

    public boolean isImportantStatus(String status) {
        return pushConfig.isImportantStatus(status);
    }

    public String getPushStrategy(String status) {
        return pushConfig.getPushStrategy(status);
    }

    public int getBatchThreshold() {
        return pushConfig.getBatchThreshold();
    }
}
