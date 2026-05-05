package com.orderflow.payment;

import com.alibaba.fastjson.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class PaymentResultCache {

    private static final Logger logger = LoggerFactory.getLogger(PaymentResultCache.class);

    private static final String PAYMENT_STATUS_PREFIX = "payment:status:";
    private static final long CACHE_EXPIRE_HOURS = 24;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    public void savePaymentStatus(String paymentId, String status, String transactionId) {
        String key = PAYMENT_STATUS_PREFIX + paymentId;
        PaymentStatusInfo info = new PaymentStatusInfo(status, transactionId, System.currentTimeMillis());
        String value = JSON.toJSONString(info);

        redisTemplate.opsForValue().set(key, value, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
        logger.debug("缓存支付状态，支付ID: {}, 状态: {}", paymentId, status);
    }

    public PaymentStatusInfo getPaymentStatus(String paymentId) {
        String key = PAYMENT_STATUS_PREFIX + paymentId;
        String value = redisTemplate.opsForValue().get(key);

        if (value == null) {
            logger.debug("支付状态缓存不存在，支付ID: {}", paymentId);
            return null;
        }

        try {
            return JSON.parseObject(value, PaymentStatusInfo.class);
        } catch (Exception e) {
            logger.warn("解析支付状态缓存失败，支付ID: {}", paymentId, e);
            return null;
        }
    }

    public void removePaymentStatus(String paymentId) {
        String key = PAYMENT_STATUS_PREFIX + paymentId;
        redisTemplate.delete(key);
        logger.debug("移除支付状态缓存，支付ID: {}", paymentId);
    }

    public static class PaymentStatusInfo {
        private String status;
        private String transactionId;
        private long timestamp;

        public PaymentStatusInfo() {
        }

        public PaymentStatusInfo(String status, String transactionId, long timestamp) {
            this.status = status;
            this.transactionId = transactionId;
            this.timestamp = timestamp;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getTransactionId() {
            return transactionId;
        }

        public void setTransactionId(String transactionId) {
            this.transactionId = transactionId;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(long timestamp) {
            this.timestamp = timestamp;
        }
    }
}
