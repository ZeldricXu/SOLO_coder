package com.metricplatform.service.channel;

import com.metricplatform.entity.SysNotificationRecord;
import com.metricplatform.service.NotificationChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class EmailChannel implements NotificationChannel {

    @Override
    public String getChannelName() {
        return "email";
    }

    @Override
    public boolean send(SysNotificationRecord record) {
        try {
            log.info("[Email] 发送邮件到: {}, 主题: {}", record.getReceiver(), record.getSubject());
            log.debug("[Email] 邮件内容: {}", record.getContent());
            return true;
        } catch (Exception e) {
            log.error("[Email] 发送邮件失败: {}", e.getMessage(), e);
            return false;
        }
    }
}
