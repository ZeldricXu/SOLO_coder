package com.paygateway.signature;

import java.util.Map;

public interface SignatureStrategy {
    
    String getChannel();
    
    String sign(Map<String, String> params, String privateKey, String charset);
    
    boolean verify(Map<String, String> params, String publicKey, String charset, String signType);
    
    String generateSignContent(Map<String, String> params, boolean excludeSign);
}
