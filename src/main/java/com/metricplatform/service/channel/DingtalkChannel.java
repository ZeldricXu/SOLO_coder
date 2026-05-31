package com.metricplatform.service.channel;

import com.metricplatform.entity.SysNotificationRecord;
import com.metricplatform.service.NotificationChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DingtalkChannel implements NotificationChannel {

    @Override
    public String getChannelName() {
        return "dingtalk";
    }

    @Override
    public boolean send(SysNotificationRecord record) {
        try {
            log.info("[DingTalk] 发送钉钉通知到: {}", record.getReceiver());
            log.debug("[DingTalk] 通知内容: {}", record.getContent());
            return true;
        } catch (Exception e) {
            log.error("[DingTalk] 发送钉钉通知失败: {}", e.getMessage(), e);
            return false;
        }
    }
}
