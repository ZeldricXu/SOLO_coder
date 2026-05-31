package com.metricplatform.service.channel;

import com.metricplatform.entity.SysNotificationRecord;
import com.metricplatform.service.NotificationChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class WechatChannel implements NotificationChannel {

    @Override
    public String getChannelName() {
        return "wechat";
    }

    @Override
    public boolean send(SysNotificationRecord record) {
        try {
            log.info("[WeChat] 发送微信通知到: {}", record.getReceiver());
            log.debug("[WeChat] 通知内容: {}", record.getContent());
            return true;
        } catch (Exception e) {
            log.error("[WeChat] 发送微信通知失败: {}", e.getMessage(), e);
            return false;
        }
    }
}
