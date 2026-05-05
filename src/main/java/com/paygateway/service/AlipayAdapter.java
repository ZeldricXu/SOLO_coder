package com.paygateway.service;

import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.domain.AlipayTradeRefundModel;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.response.AlipayTradePagePayResponse;
import com.alipay.api.response.AlipayTradeRefundResponse;
import com.paygateway.dto.CreatePaymentRequest;
import com.paygateway.dto.CreatePaymentResponse;
import com.paygateway.dto.RefundRequest;
import com.paygateway.dto.RefundResponse;
import com.paygateway.entity.ChannelConfig;
import com.paygateway.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class AlipayAdapter implements PaymentAdapter {
    
    @Value("${pay.gateway.base-url:http://localhost:8080}")
    private String gatewayBaseUrl;
    
    private static final String GATEWAY_URL = "https://openapi.alipay.com/gateway.do";
    private static final String FORMAT = "json";
    private static final String CHARSET = "UTF-8";
    private static final String SIGN_TYPE = "RSA2";
    
    @Override
    public String getChannel() {
        return "alipay";
    }
    
    @Override
    public CreatePaymentResponse createOrder(CreatePaymentRequest request, ChannelConfig config, String gatewayOrderId) {
        try {
            AlipayClient alipayClient = createAlipayClient(config);
            
            AlipayTradePagePayRequest alipayRequest = new AlipayTradePagePayRequest();
            
            String notifyUrl = config.getNotifyUrl();
            if (StrUtil.isBlank(notifyUrl)) {
                notifyUrl = gatewayBaseUrl + "/api/v1/callback/alipay";
            }
            alipayRequest.setNotifyUrl(notifyUrl);
            
            AlipayTradePagePayModel model = new AlipayTradePagePayModel();
            model.setOutTradeNo(gatewayOrderId);
            model.setTotalAmount(request.getAmount().toString());
            model.setSubject(StrUtil.isNotBlank(request.getProductDesc()) ? request.getProductDesc() : "商品购买");
            model.setProductCode("FAST_INSTANT_TRADE_PAY");
            
            alipayRequest.setBizModel(model);
            
            AlipayTradePagePayResponse response = alipayClient.pageExecute(alipayRequest);
            
            if (response.isSuccess()) {
                CreatePaymentResponse payResponse = new CreatePaymentResponse();
                payResponse.setPayUrl(response.getBody());
                log.info("支付宝下单成功：gatewayOrderId={}, form={}", gatewayOrderId, response.getBody());
                return payResponse;
            } else {
                log.error("支付宝下单失败：code={}, msg={}", response.getCode(), response.getMsg());
                throw new BusinessException("支付宝下单失败：" + response.getMsg());
            }
        } catch (AlipayApiException e) {
            log.error("支付宝下单异常", e);
            throw new BusinessException("支付宝下单异常：" + e.getErrMsg());
        }
    }
    
    @Override
    public RefundResponse refund(RefundRequest request, ChannelConfig config, String gatewayRefundId, String channelOrderNo) {
        try {
            AlipayClient alipayClient = createAlipayClient(config);
            
            AlipayTradeRefundRequest refundRequest = new AlipayTradeRefundRequest();
            
            AlipayTradeRefundModel model = new AlipayTradeRefundModel();
            model.setOutTradeNo(channelOrderNo);
            model.setOutRequestNo(gatewayRefundId);
            model.setRefundAmount(request.getAmount().toString());
            model.setRefundReason(StrUtil.isNotBlank(request.getReason()) ? request.getReason() : "退款");
            
            refundRequest.setBizModel(model);
            
            AlipayTradeRefundResponse response = alipayClient.execute(refundRequest);
            
            if (response.isSuccess()) {
                RefundResponse refundResponse = new RefundResponse();
                refundResponse.setGatewayRefundId(gatewayRefundId);
                refundResponse.setStatus("success");
                refundResponse.setAmount(request.getAmount());
                log.info("支付宝退款成功：gatewayRefundId={}", gatewayRefundId);
                return refundResponse;
            } else {
                log.error("支付宝退款失败：code={}, msg={}", response.getCode(), response.getMsg());
                throw new BusinessException("支付宝退款失败：" + response.getMsg());
            }
        } catch (AlipayApiException e) {
            log.error("支付宝退款异常", e);
            throw new BusinessException("支付宝退款异常：" + e.getErrMsg());
        }
    }
    
    @Override
    public boolean verifySignature(Map<String, String> params, ChannelConfig config) {
        try {
            String publicKey = config.getPublicKey();
            boolean signVerified = AlipaySignature.rsaCheckV1(params, publicKey, CHARSET, SIGN_TYPE);
            log.info("支付宝签名验证结果：{}", signVerified);
            return signVerified;
        } catch (AlipayApiException e) {
            log.error("支付宝签名验证异常", e);
            return false;
        }
    }
    
    @Override
    public Map<String, String> parseCallbackParams(String body, Map<String, String> headers) {
        Map<String, String> params = new HashMap<>();
        
        if (StrUtil.isNotBlank(body)) {
            String[] pairs = body.split("&");
            for (String pair : pairs) {
                String[] keyValue = pair.split("=", 2);
                if (keyValue.length == 2) {
                    String key = keyValue[0];
                    String value = URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8);
                    params.put(key, value);
                }
            }
        }
        
        log.info("解析支付宝回调参数：{}", JSONUtil.toJsonStr(params));
        return params;
    }
    
    @Override
    public String getSuccessResponse() {
        return "success";
    }
    
    @Override
    public String getFailResponse() {
        return "fail";
    }
    
    private AlipayClient createAlipayClient(ChannelConfig config) {
        return new DefaultAlipayClient(
                GATEWAY_URL,
                config.getAppId(),
                config.getPrivateKey(),
                FORMAT,
                CHARSET,
                config.getPublicKey(),
                SIGN_TYPE
        );
    }
}
