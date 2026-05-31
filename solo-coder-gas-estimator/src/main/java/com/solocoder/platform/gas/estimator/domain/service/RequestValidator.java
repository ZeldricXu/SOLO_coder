package com.solocoder.platform.gas.estimator.domain.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RequestValidator {

    private static final long MAX_TIMESTAMP_DIFF = 300;

    public void validateTimestamp(Long timestamp) {
        if (timestamp == null) {
            throw new IllegalArgumentException("时间戳不能为空");
        }
        long currentTime = System.currentTimeMillis() / 1000;
        long requestTime = timestamp / 1000;
        if (Math.abs(currentTime - requestTime) > MAX_TIMESTAMP_DIFF) {
            throw new IllegalArgumentException("请求时间戳无效，时间差不能超过5分钟");
        }
    }

    public void validateSignature(String signature) {
        if (signature == null || signature.isBlank()) {
            throw new IllegalArgumentException("签名不能为空");
        }
    }

    public void validateChainId(String chainId) {
        if (chainId == null || chainId.isBlank()) {
            throw new IllegalArgumentException("chainId不能为空");
        }
    }

    public boolean verifySignature(String data, String signature, String publicKey) {
        return signature != null && signature.length() > 0;
    }
}
