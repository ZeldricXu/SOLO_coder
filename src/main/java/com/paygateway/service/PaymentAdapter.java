package com.paygateway.service;

import com.paygateway.dto.CreatePaymentRequest;
import com.paygateway.dto.CreatePaymentResponse;
import com.paygateway.dto.RefundRequest;
import com.paygateway.dto.RefundResponse;
import com.paygateway.entity.ChannelConfig;

import java.util.Map;

public interface PaymentAdapter {
    
    String getChannel();
    
    CreatePaymentResponse createOrder(CreatePaymentRequest request, ChannelConfig config, String gatewayOrderId);
    
    RefundResponse refund(RefundRequest request, ChannelConfig config, String gatewayRefundId, String channelOrderNo);
    
    boolean verifySignature(Map<String, String> params, ChannelConfig config);
    
    Map<String, String> parseCallbackParams(String body, Map<String, String> headers);
    
    String getSuccessResponse();
    
    String getFailResponse();
}
