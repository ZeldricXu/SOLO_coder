package com.paygateway.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.paygateway.dto.CreatePaymentRequest;
import com.paygateway.dto.CreatePaymentResponse;
import com.paygateway.dto.RefundRequest;
import com.paygateway.dto.RefundResponse;
import com.paygateway.entity.ChannelConfig;
import com.paygateway.exception.BusinessException;
import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.RSAAutoCertificateConfig;
import com.wechat.pay.java.core.notification.NotificationConfig;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.payments.nativepay.NativePayService;
import com.wechat.pay.java.service.payments.nativepay.model.Amount;
import com.wechat.pay.java.service.payments.nativepay.model.PrepayRequest;
import com.wechat.pay.java.service.payments.nativepay.model.PrepayResponse;
import com.wechat.pay.java.service.refund.RefundService;
import com.wechat.pay.java.service.refund.model.CreateRequest;
import com.wechat.pay.java.service.refund.model.Refund;
import com.wechat.pay.java.service.refund.model.RefundAmount;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class WechatPayAdapter implements PaymentAdapter {
    
    @Value("${pay.gateway.base-url:http://localhost:8080}")
    private String gatewayBaseUrl;
    
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    
    @Override
    public String getChannel() {
        return "wechat";
    }
    
    @Override
    public CreatePaymentResponse createOrder(CreatePaymentRequest request, ChannelConfig config, String gatewayOrderId) {
        try {
            Config configObj = createWechatConfig(config);
            NativePayService service = new NativePayService.Builder().config(configObj).build();
            
            PrepayRequest prepayRequest = new PrepayRequest();
            prepayRequest.setAppid(config.getAppId());
            prepayRequest.setMchid(config.getChannelMerchantId());
            prepayRequest.setDescription(StrUtil.isNotBlank(request.getProductDesc()) ? request.getProductDesc() : "商品购买");
            prepayRequest.setOutTradeNo(gatewayOrderId);
            prepayRequest.setNotifyUrl(gatewayBaseUrl + "/api/v1/callback/wechat");
            
            Amount amount = new Amount();
            amount.setTotal(request.getAmount().multiply(new java.math.BigDecimal("100")).intValue());
            amount.setCurrency("CNY");
            prepayRequest.setAmount(amount);
            
            PrepayResponse response = service.prepay(prepayRequest);
            
            CreatePaymentResponse payResponse = new CreatePaymentResponse();
            payResponse.setCodeUrl(response.getCodeUrl());
            
            log.info("微信支付下单成功：gatewayOrderId={}, codeUrl={}", gatewayOrderId, response.getCodeUrl());
            return payResponse;
        } catch (Exception e) {
            log.error("微信支付下单异常", e);
            throw new BusinessException("微信支付下单失败：" + e.getMessage());
        }
    }
    
    @Override
    public RefundResponse refund(RefundRequest request, ChannelConfig config, String gatewayRefundId, String channelOrderNo) {
        try {
            Config configObj = createWechatConfig(config);
            RefundService service = new RefundService.Builder().config(configObj).build();
            
            CreateRequest createRequest = new CreateRequest();
            createRequest.setOutTradeNo(channelOrderNo);
            createRequest.setOutRefundNo(gatewayRefundId);
            
            RefundAmount amount = new RefundAmount();
            amount.setRefund(request.getAmount().multiply(new java.math.BigDecimal("100")).intValue());
            amount.setTotal(request.getAmount().multiply(new java.math.BigDecimal("100")).intValue());
            amount.setCurrency("CNY");
            createRequest.setAmount(amount);
            
            Refund response = service.create(createRequest);
            
            RefundResponse refundResponse = new RefundResponse();
            refundResponse.setGatewayRefundId(gatewayRefundId);
            refundResponse.setChannelRefundNo(response.getRefundId());
            refundResponse.setStatus("success");
            refundResponse.setAmount(request.getAmount());
            
            log.info("微信退款成功：gatewayRefundId={}, channelRefundNo={}", gatewayRefundId, response.getRefundId());
            return refundResponse;
        } catch (Exception e) {
            log.error("微信退款异常", e);
            throw new BusinessException("微信退款失败：" + e.getMessage());
        }
    }
    
    @Override
    public boolean verifySignature(Map<String, String> params, ChannelConfig config) {
        try {
            String signature = params.get("sign");
            String timestamp = params.get("timestamp");
            String nonce = params.get("nonce");
            String body = params.get("body");
            
            RequestParam requestParam = new RequestParam.Builder()
                    .serialNumber(params.get("serial"))
                    .nonce(nonce)
                    .signature(signature)
                    .timestamp(timestamp)
                    .body(body)
                    .build();
            
            NotificationConfig notificationConfig = createNotificationConfig(config);
            NotificationParser parser = new NotificationParser(notificationConfig);
            
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
            log.error("微信支付签名验证异常", e);
            return false;
        }
    }
    
    @Override
    public Map<String, String> parseCallbackParams(String body, Map<String, String> headers) {
        Map<String, String> params = new HashMap<>();
        
        params.put("body", body);
        params.put("sign", headers.get("wechatpay-signature"));
        params.put("timestamp", headers.get("wechatpay-timestamp"));
        params.put("nonce", headers.get("wechatpay-nonce"));
        params.put("serial", headers.get("wechatpay-serial"));
        
        log.info("解析微信回调参数：headers={}, body={}", JSONUtil.toJsonStr(headers), body);
        return params;
    }
    
    @Override
    public String getSuccessResponse() {
        return "SUCCESS";
    }
    
    @Override
    public String getFailResponse() {
        return "FAIL";
    }
    
    private Config createWechatConfig(ChannelConfig config) {
        return new RSAAutoCertificateConfig.Builder()
                .merchantId(config.getChannelMerchantId())
                .privateKey(config.getPrivateKey())
                .merchantSerialNumber(config.getAppId())
                .apiV3Key(config.getPublicKey())
                .build();
    }
    
    private NotificationConfig createNotificationConfig(ChannelConfig config) {
        return new RSAAutoCertificateConfig.Builder()
                .merchantId(config.getChannelMerchantId())
                .privateKey(config.getPrivateKey())
                .merchantSerialNumber(config.getAppId())
                .apiV3Key(config.getPublicKey())
                .build();
    }
}
