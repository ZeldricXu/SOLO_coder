package com.paygateway.dto;

import lombok.Data;

@Data
public class CreatePaymentResponse {
    
    private String gatewayOrderId;
    private String payUrl;
    private String codeUrl;
    private String prepayId;
    private String appId;
    private String timeStamp;
    private String nonceStr;
    private String packageStr;
    private String signType;
    private String paySign;
}
