package com.paygateway.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONUtil;
import com.paygateway.entity.PaymentOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncNotificationService {
    
    private final RedisNotificationQueueService redisNotificationQueueService;
    
    @Async("notificationExecutor")
    public void sendNotificationAsync(PaymentOrder order) {
        if (StrUtil.isBlank(order.getNotifyUrl())) {
            log.info("商户回调URL为空，跳过通知：orderId={}", order.getOrderId());
            return;
        }
        
        log.info("异步发送商户通知：orderId={}, notifyUrl={}", order.getOrderId(), order.getNotifyUrl());
        
        try {
            NotificationQueueItem item = buildQueueItem(order);
            
            boolean success = doSendNotification(item);
            
            if (success) {
                log.info("商户通知发送成功：orderId={}", order.getOrderId());
            } else {
                log.warn("商户通知发送失败，加入Redis重试队列：orderId={}", order.getOrderId());
                redisNotificationQueueService.addToQueue(item);
            }
        } catch (Exception e) {
            log.error("异步通知发送异常：orderId={}", order.getOrderId(), e);
            try {
                NotificationQueueItem item = buildQueueItem(order);
                redisNotificationQueueService.addToQueue(item);
            } catch (Exception ex) {
                log.error("加入重试队列失败：orderId={}", order.getOrderId(), ex);
            }
        }
    }
    
    public boolean executeNotification(NotificationQueueItem item) {
        log.info("执行通知发送：retryId={}, orderId={}, retryCount={}", 
                item.getRetryId(), item.getOrderId(), item.getRetryCount());
        
        try {
            boolean success = doSendNotification(item);
            
            if (success) {
                redisNotificationQueueService.markAsSuccess(item.getRetryId());
                return true;
            } else {
                redisNotificationQueueService.markForRetry(item, "商户响应失败");
                return false;
            }
        } catch (Exception e) {
            log.error("通知发送异常：retryId={}", item.getRetryId(), e);
            redisNotificationQueueService.markForRetry(item, e.getMessage());
            return false;
        }
    }
    
    private boolean doSendNotification(NotificationQueueItem item) {
        try {
            String notifyContent = item.getNotifyContent();
            if (notifyContent == null) {
                notifyContent = buildNotifyContent(item);
            }
            
            String response = HttpRequest.post(item.getNotifyUrl())
                    .header("Content-Type", "application/json")
                    .body(notifyContent)
                    .timeout(10000)
                    .execute()
                    .body();
            
            log.info("商户通知响应：orderId={}, response={}", item.getOrderId(), response);
            
            return isSuccessResponse(response);
        } catch (Exception e) {
            log.error("发送HTTP请求失败：orderId={}", item.getOrderId(), e);
            return false;
        }
    }
    
    private boolean isSuccessResponse(String response) {
        if (response == null) {
            return false;
        }
        String lowerResponse = response.trim().toLowerCase();
        return lowerResponse.equals("success") 
                || lowerResponse.equals("ok")
                || lowerResponse.contains("\"code\":200")
                || lowerResponse.contains("\"success\":true");
    }
    
    private NotificationQueueItem buildQueueItem(PaymentOrder order) {
        NotificationQueueItem item = new NotificationQueueItem();
        item.setOrderId(order.getOrderId());
        item.setMerchantId(order.getMerchantId());
        item.setMerchantOrderNo(order.getMerchantOrderNo());
        item.setChannelOrderNo(order.getChannelOrderNo());
        item.setNotifyUrl(order.getNotifyUrl());
        item.setAmount(order.getAmount());
        item.setStatus(order.getStatus().getCode());
        item.setChannel(order.getChannel());
        item.setPaidAt(order.getPaidAt());
        item.setNotifyContent(buildNotifyContent(order));
        return item;
    }
    
    private String buildNotifyContent(PaymentOrder order) {
        Map<String, Object> notifyData = new HashMap<>();
        notifyData.put("orderId", order.getOrderId());
        notifyData.put("merchantOrderNo", order.getMerchantOrderNo());
        notifyData.put("amount", order.getAmount());
        notifyData.put("status", order.getStatus().getCode());
        notifyData.put("channelOrderNo", order.getChannelOrderNo());
        notifyData.put("paidAt", order.getPaidAt());
        notifyData.put("channel", order.getChannel());
        return JSONUtil.toJsonStr(notifyData);
    }
    
    private String buildNotifyContent(NotificationQueueItem item) {
        Map<String, Object> notifyData = new HashMap<>();
        notifyData.put("orderId", item.getOrderId());
        notifyData.put("merchantOrderNo", item.getMerchantOrderNo());
        notifyData.put("amount", item.getAmount());
        notifyData.put("status", item.getStatus());
        notifyData.put("channelOrderNo", item.getChannelOrderNo());
        notifyData.put("paidAt", item.getPaidAt());
        notifyData.put("channel", item.getChannel());
        return JSONUtil.toJsonStr(notifyData);
    }
}
