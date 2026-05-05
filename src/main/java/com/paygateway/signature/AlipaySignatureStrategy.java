package com.paygateway.signature;

import com.alipay.api.internal.util.AlipaySignature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class AlipaySignatureStrategy implements SignatureStrategy {
    
    private static final String SIGN_TYPE_RSA2 = "RSA2";
    private static final String SIGN_FIELD = "sign";
    private static final String SIGN_TYPE_FIELD = "sign_type";
    
    @Override
    public String getChannel() {
        return "alipay";
    }
    
    @Override
    public String sign(Map<String, String> params, String privateKey, String charset) {
        try {
            String signContent = generateSignContent(params, true);
            return AlipaySignature.rsaSign(signContent, privateKey, charset, SIGN_TYPE_RSA2);
        } catch (Exception e) {
            log.error("支付宝签名失败", e);
            throw new RuntimeException("支付宝签名失败", e);
        }
    }
    
    @Override
    public boolean verify(Map<String, String> params, String publicKey, String charset, String signType) {
        try {
            String actualSignType = signType != null ? signType : 
                    (params.get(SIGN_TYPE_FIELD) != null ? params.get(SIGN_TYPE_FIELD) : SIGN_TYPE_RSA2);
            return AlipaySignature.rsaCheckV1(params, publicKey, charset, actualSignType);
        } catch (Exception e) {
            log.error("支付宝签名验证失败", e);
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
            if (excludeSign && (SIGN_FIELD.equals(key) || SIGN_TYPE_FIELD.equals(key))) {
                continue;
            }
            String value = params.get(key);
            if (value != null && !value.isEmpty()) {
                if (content.length() > 0) {
                    content.append("&");
                }
                content.append(key).append("=").append(value);
            }
        }
        
        return content.toString();
    }
}
