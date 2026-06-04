package com.flowplatform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flowplatform.entity.Notification;
import com.flowplatform.mapper.NotificationMapper;
import com.flowplatform.service.NotificationService;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class NotificationServiceImpl extends ServiceImpl<NotificationMapper, Notification> implements NotificationService {

    @Override
    public int countUnread(Long userId) {
        return (int) count(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getIsRead, 0));
    }

    @Override
    public List<Notification> listByUserId(Long userId) {
        return list(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .orderByDesc(Notification::getCreateTime));
    }

    @Override
    public boolean markRead(Long notificationId) {
        Notification n = getById(notificationId);
        if (n != null) {
            n.setIsRead(1);
            return updateById(n);
        }
        return false;
    }

    @Override
    public boolean markAllRead(Long userId) {
        update(new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getIsRead, 0)
                .set(Notification::getIsRead, 1));
        return true;
    }

    @Override
    public void sendNotification(Long userId, String title, String content, String type, String bizType, Long bizId) {
        Notification n = new Notification();
        n.setUserId(userId);
        n.setTitle(title);
        n.setContent(content);
        n.setNotificationType(type);
        n.setBizType(bizType);
        n.setBizId(bizId);
        n.setIsRead(0);
        save(n);
    }
}
