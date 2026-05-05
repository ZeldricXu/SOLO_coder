package com.paygateway.signature;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class SignatureUtils {
    
    private final Map<String, SignatureStrategy> strategyMap;
    private static final String DEFAULT_CHARSET = "UTF-8";
    
    public SignatureUtils(List<SignatureStrategy> strategies) {
        this.strategyMap = new ConcurrentHashMap<>();
        for (SignatureStrategy strategy : strategies) {
            this.strategyMap.put(strategy.getChannel(), strategy);
        }
        log.info("已注册签名策略：{}", strategyMap.keySet());
    }
    
    public String sign(String channel, Map<String, String> params, String privateKey) {
        return sign(channel, params, privateKey, DEFAULT_CHARSET);
    }
    
    public String sign(String channel, Map<String, String> params, String privateKey, String charset) {
        SignatureStrategy strategy = getStrategy(channel);
        return strategy.sign(params, privateKey, charset);
    }
    
    public boolean verify(String channel, Map<String, String> params, String publicKey) {
        return verify(channel, params, publicKey, DEFAULT_CHARSET, null);
    }
    
    public boolean verify(String channel, Map<String, String> params, String publicKey, String charset, String signType) {
        SignatureStrategy strategy = getStrategy(channel);
        return strategy.verify(params, publicKey, charset, signType);
    }
    
    public String generateSignContent(String channel, Map<String, String> params, boolean excludeSign) {
        SignatureStrategy strategy = getStrategy(channel);
        return strategy.generateSignContent(params, excludeSign);
    }
    
    public boolean supportsChannel(String channel) {
        return strategyMap.containsKey(channel);
    }
    
    private SignatureStrategy getStrategy(String channel) {
        SignatureStrategy strategy = strategyMap.get(channel);
        if (strategy == null) {
            throw new IllegalArgumentException("不支持的支付渠道签名策略：" + channel);
        }
        return strategy;
    }
    
    public void registerStrategy(SignatureStrategy strategy) {
        strategyMap.put(strategy.getChannel(), strategy);
        log.info("注册新的签名策略：{}", strategy.getChannel());
    }
}
