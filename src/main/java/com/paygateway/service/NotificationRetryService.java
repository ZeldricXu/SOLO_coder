package com.paygateway.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONUtil;
import com.paygateway.entity.NotificationRetry;
import com.paygateway.entity.PaymentOrder;
import com.paygateway.repository.NotificationRetryRepository;
import com.paygateway.repository.PaymentOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationRetryService {
    
    private final NotificationRetryRepository notificationRetryRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    
    private static final int MAX_RETRY_COUNT = 5;
    private static final int[] RETRY_INTERVALS = {1, 5, 10, 30, 60};
    
    @Transactional
    public NotificationRetry createRetry(PaymentOrder order) {
        if (StrUtil.isBlank(order.getNotifyUrl())) {
            log.info("商户回调URL为空，跳过创建重试记录：orderId={}", order.getOrderId());
            return null;
        }
        
        if (notificationRetryRepository.existsByOrderIdAndStatus(order.getOrderId(), "pending")) {
            log.info("订单已存在待处理的重试记录：orderId={}", order.getOrderId());
            return null;
        }
        
        NotificationRetry retry = new NotificationRetry();
        retry.setRetryId(generateRetryId());
        retry.setOrderId(order.getOrderId());
        retry.setMerchantId(order.getMerchantId());
        retry.setNotifyUrl(order.getNotifyUrl());
        retry.setNotifyContent(buildNotifyContent(order));
        retry.setRetryCount(0);
        retry.setMaxRetryCount(MAX_RETRY_COUNT);
        retry.setStatus("pending");
        retry.setNextRetryAt(LocalDateTime.now());
        
        return notificationRetryRepository.save(retry);
    }
    
    @Transactional
    public NotificationRetry createRetry(String orderId) {
        Optional<PaymentOrder> orderOpt = paymentOrderRepository.findByOrderId(orderId);
        if (orderOpt.isEmpty()) {
            throw new IllegalArgumentException("订单不存在：" + orderId);
        }
        return createRetry(orderOpt.get());
    }
    
    @Transactional
    public boolean executeRetry(NotificationRetry retry) {
        log.info("执行商户通知重试：retryId={}, orderId={}, retryCount={}", 
                retry.getRetryId(), retry.getOrderId(), retry.getRetryCount());
        
        try {
            String response = HttpRequest.post(retry.getNotifyUrl())
                    .header("Content-Type", "application/json")
                    .body(retry.getNotifyContent())
                    .timeout(10000)
                    .execute()
                    .body();
            
            log.info("商户通知响应：retryId={}, response={}", retry.getRetryId(), response);
            
            if (isSuccessResponse(response)) {
                retry.setStatus("success");
                retry.setLastNotifyAt(LocalDateTime.now());
                notificationRetryRepository.save(retry);
                log.info("商户通知重试成功：retryId={}", retry.getRetryId());
                return true;
            } else {
                handleRetryFailure(retry, "商户响应失败：" + response);
                return false;
            }
        } catch (Exception e) {
            log.error("商户通知重试异常：retryId={}", retry.getRetryId(), e);
            handleRetryFailure(retry, e.getMessage());
            return false;
        }
    }
    
    private void handleRetryFailure(NotificationRetry retry, String errorMsg) {
        int newRetryCount = retry.getRetryCount() + 1;
        retry.setRetryCount(newRetryCount);
        retry.setLastErrorMsg(errorMsg);
        retry.setLastNotifyAt(LocalDateTime.now());
        
        if (newRetryCount >= retry.getMaxRetryCount()) {
            retry.setStatus("failed");
            log.error("商户通知重试已达最大次数，标记为失败：retryId={}, maxRetryCount={}", 
                    retry.getRetryId(), retry.getMaxRetryCount());
        } else {
            int intervalMinutes = RETRY_INTERVALS[Math.min(newRetryCount - 1, RETRY_INTERVALS.length - 1)];
            retry.setNextRetryAt(LocalDateTime.now().plusMinutes(intervalMinutes));
            retry.setStatus("pending");
            log.info("商户通知重试安排下次执行：retryId={}, nextRetryAt={}", 
                    retry.getRetryId(), retry.getNextRetryAt());
        }
        
        notificationRetryRepository.save(retry);
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
    
    public List<NotificationRetry> getPendingRetries() {
        return notificationRetryRepository.findPendingRetries("pending", LocalDateTime.now());
    }
    
    public List<NotificationRetry> getFailedRetries() {
        return notificationRetryRepository.findFailedRetries("failed");
    }
    
    public Optional<NotificationRetry> findByRetryId(String retryId) {
        return notificationRetryRepository.findByRetryId(retryId);
    }
    
    public Optional<NotificationRetry> findByOrderId(String orderId) {
        return notificationRetryRepository.findByOrderId(orderId);
    }
    
    public List<NotificationRetry> findByMerchantId(String merchantId) {
        return notificationRetryRepository.findByMerchantId(merchantId);
    }
    
    @Transactional
    public boolean manualRetry(String retryId) {
        Optional<NotificationRetry> retryOpt = notificationRetryRepository.findByRetryId(retryId);
        if (retryOpt.isEmpty()) {
            throw new IllegalArgumentException("重试记录不存在：" + retryId);
        }
        
        NotificationRetry retry = retryOpt.get();
        retry.setRetryCount(0);
        retry.setStatus("pending");
        retry.setNextRetryAt(LocalDateTime.now());
        notificationRetryRepository.save(retry);
        
        return executeRetry(retry);
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
    
    private String generateRetryId() {
        return "RET" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }
}
