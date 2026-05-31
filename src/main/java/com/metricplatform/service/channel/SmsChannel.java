package com.metricplatform.service.channel;

import com.metricplatform.entity.SysNotificationRecord;
import com.metricplatform.service.NotificationChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SmsChannel implements NotificationChannel {

    @Override
    public String getChannelName() {
        return "sms";
    }

    @Override
    public boolean send(SysNotificationRecord record) {
        try {
            log.info("[SMS] 发送短信到: {}", record.getReceiver());
            log.debug("[SMS] 短信内容: {}", record.getContent());
            return true;
        } catch (Exception e) {
            log.error("[SMS] 发送短信失败: {}", e.getMessage(), e);
            return false;
        }
    }
}
