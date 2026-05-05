package com.paygateway.service;

import cn.hutool.core.util.StrUtil;
import com.paygateway.entity.ChannelConfig;
import com.paygateway.entity.PaymentOrder;
import com.paygateway.repository.ChannelConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CallbackService {
    
    private final PaymentAdapterFactory paymentAdapterFactory;
    private final ChannelConfigRepository channelConfigRepository;
    private final OrderTransactionService orderTransactionService;
    private final AsyncNotificationService asyncNotificationService;
    
    public String handleCallback(String channel, String body, Map<String, String> headers) {
        log.info("收到{}回调通知", channel);
        
        PaymentAdapter adapter = paymentAdapterFactory.getAdapter(channel);
        
        Map<String, String> params = adapter.parseCallbackParams(body, headers);
        
        Optional<ChannelConfig> configOpt = findChannelConfig(params, channel);
        if (configOpt.isEmpty()) {
            log.error("找不到对应的渠道配置：channel={}", channel);
            return adapter.getFailResponse();
        }
        
        ChannelConfig config = configOpt.get();
        
        if (!adapter.verifySignature(params, config)) {
            log.error("{}回调签名验证失败", channel);
            return adapter.getFailResponse();
        }
        
        String orderId = extractOrderId(params, channel);
        String channelOrderNo = extractChannelOrderNo(params, channel);
        String tradeStatus = extractTradeStatus(params, channel);
        LocalDateTime paidAt = extractPaidAt(params, channel);
        
        log.info("回调解析结果：orderId={}, channelOrderNo={}, tradeStatus={}", 
                orderId, channelOrderNo, tradeStatus);
        
        if (orderId == null) {
            log.error("无法从回调中提取订单ID");
            return adapter.getFailResponse();
        }
        
        if (!isPaymentSuccess(tradeStatus)) {
            log.info("非支付成功回调，跳过处理：orderId={}, tradeStatus={}", orderId, tradeStatus);
            return adapter.getSuccessResponse();
        }
        
        try {
            PaymentOrder updatedOrder = orderTransactionService.markOrderAsPaid(orderId, channelOrderNo, paidAt);
            
            log.info("订单支付成功：orderId={}, channelOrderNo={}", orderId, channelOrderNo);
            
            if (StrUtil.isNotBlank(updatedOrder.getNotifyUrl())) {
                asyncNotificationService.sendNotificationAsync(updatedOrder);
                log.info("已提交异步通知任务：orderId={}", orderId);
            }
            
        } catch (Exception e) {
            log.error("订单状态更新失败：orderId={}", orderId, e);
            return adapter.getFailResponse();
        }
        
        return adapter.getSuccessResponse();
    }
    
    private boolean isPaymentSuccess(String tradeStatus) {
        if (tradeStatus == null) {
            return false;
        }
        String upperStatus = tradeStatus.toUpperCase();
        return upperStatus.equals("SUCCESS") 
                || upperStatus.equals("TRADE_SUCCESS")
                || upperStatus.equals("PAID");
    }
    
    private Optional<ChannelConfig> findChannelConfig(Map<String, String> params, String channel) {
        String orderId = extractOrderId(params, channel);
        if (orderId != null) {
            Optional<PaymentOrder> orderOpt = orderTransactionService.findOrder(orderId);
            if (orderOpt.isPresent()) {
                return channelConfigRepository.findByMerchantIdAndChannel(
                        orderOpt.get().getMerchantId(), channel);
            }
        }
        return Optional.empty();
    }
    
    private String extractOrderId(Map<String, String> params, String channel) {
        if ("alipay".equals(channel)) {
            return params.get("out_trade_no");
        } else if ("wechat".equals(channel)) {
            return params.get("outTradeNo");
        }
        return null;
    }
    
    private String extractChannelOrderNo(Map<String, String> params, String channel) {
        if ("alipay".equals(channel)) {
            return params.get("trade_no");
        } else if ("wechat".equals(channel)) {
            return params.get("transactionId");
        }
        return null;
    }
    
    private String extractTradeStatus(Map<String, String> params, String channel) {
        if ("alipay".equals(channel)) {
            return params.get("trade_status");
        } else if ("wechat".equals(channel)) {
            return params.get("tradeState");
        }
        return null;
    }
    
    private LocalDateTime extractPaidAt(Map<String, String> params, String channel) {
        String timeStr = null;
        if ("alipay".equals(channel)) {
            timeStr = params.get("gmt_payment");
        } else if ("wechat".equals(channel)) {
            timeStr = params.get("successTime");
        }
        
        if (StrUtil.isNotBlank(timeStr)) {
            try {
                if (timeStr.contains("T") && timeStr.contains("Z")) {
                    return LocalDateTime.ofInstant(Instant.parse(timeStr), ZoneId.systemDefault());
                }
                return LocalDateTime.parse(timeStr);
            } catch (Exception e) {
                log.warn("解析支付时间失败：{}", timeStr, e);
            }
        }
        return null;
    }
}
