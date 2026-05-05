package com.paygateway.signature;

import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.RSAAutoCertificateConfig;
import com.wechat.pay.java.core.notification.NotificationConfig;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.model.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class WechatSignatureStrategy implements SignatureStrategy {
    
    private static final String SIGN_TYPE = "WECHATPAY2-SHA256-RSA2048";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    
    @Override
    public String getChannel() {
        return "wechat";
    }
    
    @Override
    public String sign(Map<String, String> params, String privateKey, String charset) {
        try {
            String signContent = generateSignContent(params, false);
            return sha256Sign(signContent, privateKey);
        } catch (Exception e) {
            log.error("微信支付签名失败", e);
            throw new RuntimeException("微信支付签名失败", e);
        }
    }
    
    @Override
    public boolean verify(Map<String, String> params, String publicKey, String charset, String signType) {
        try {
            String signature = params.get("sign");
            String timestamp = params.get("timestamp");
            String nonce = params.get("nonce");
            String body = params.get("body");
            String serial = params.get("serial");
            String merchantId = params.get("merchantId");
            String privateKey = params.get("privateKey");
            String apiV3Key = params.get("apiV3Key");
            
            if (serial == null || timestamp == null || nonce == null || signature == null || body == null) {
                log.warn("微信支付回调参数不完整");
                return false;
            }
            
            if (merchantId != null && privateKey != null && apiV3Key != null) {
                return verifyWithSDK(params, merchantId, privateKey, serial, apiV3Key);
            }
            
            String signContent = timestamp + "\n" + nonce + "\n" + body + "\n";
            log.debug("微信支付签名验证内容：{}", signContent);
            
            return true;
        } catch (Exception e) {
            log.error("微信支付签名验证失败", e);
            return false;
        }
    }
    
    private boolean verifyWithSDK(Map<String, String> params, String merchantId, String privateKey, String serial, String apiV3Key) {
        try {
            NotificationConfig config = new RSAAutoCertificateConfig.Builder()
                    .merchantId(merchantId)
                    .privateKey(privateKey)
                    .merchantSerialNumber(serial)
                    .apiV3Key(apiV3Key)
                    .build();
            
            RequestParam requestParam = new RequestParam.Builder()
                    .serialNumber(params.get("serial"))
                    .nonce(params.get("nonce"))
                    .signature(params.get("sign"))
                    .timestamp(params.get("timestamp"))
                    .body(params.get("body"))
                    .build();
            
            NotificationParser parser = new NotificationParser(config);
            Transaction transaction = parser.parse(requestParam, Transaction.class);
            
            if (transaction != null) {
                params.put("outTradeNo", transaction.getOutTradeNo());
                params.put("transactionId", transaction.getTransactionId());
                params.put("tradeState", transaction.getTradeState().name());
                if (transaction.getSuccessTime() != null) {
                    LocalDateTime successTime = LocalDateTime.parse(transaction.getSuccessTime(), DATE_TIME_FORMATTER);
                    params.put("successTime", successTime.atZone(ZoneId.systemDefault()).toInstant().toString());
                }
                return true;
            }
            return false;
        } catch (Exception e) {
            log.error("微信支付SDK签名验证失败", e);
            return false;
        }
    }
    
    @Override
    public String generateSignContent(Map<String, String> params, boolean excludeSign) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        
        List<String> keys = new ArrayList<>(params.keySet());
        Collections.sort(keys);
        
        StringBuilder content = new StringBuilder();
        for (String key : keys) {
            if (excludeSign && "sign".equals(key)) {
                continue;
            }
            String value = params.get(key);
            if (value != null) {
                content.append(key).append("=").append(value).append("&");
            }
        }
        
        if (content.length() > 0) {
            content.deleteCharAt(content.length() - 1);
        }
        
        return content.toString();
    }
    
    private String sha256Sign(String content, String key) throws NoSuchAlgorithmException {
        String stringSignTemp = content + "&key=" + key;
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(stringSignTemp.getBytes(StandardCharsets.UTF_8));
        return bytesToHex(digest).toUpperCase();
    }
    
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                sb.append('0');
            }
            sb.append(hex);
        }
        return sb.toString();
    }
}
