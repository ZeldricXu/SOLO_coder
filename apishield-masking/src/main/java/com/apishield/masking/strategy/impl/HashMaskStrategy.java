package com.apishield.masking.strategy.impl;

import com.apishield.common.util.CryptoUtil;
import com.apishield.masking.domain.MaskingPolicy;
import com.apishield.masking.strategy.MaskingStrategy;
import org.springframework.stereotype.Component;

@Component
public class HashMaskStrategy implements MaskingStrategy {

    @Override
    public MaskingPolicy.MaskingStrategyType getStrategyType() {
        return MaskingPolicy.MaskingStrategyType.HASH;
    }

    @Override
    public Object mask(Object originalValue, MaskingPolicy policy) {
        if (originalValue == null) {
            return null;
        }
        return CryptoUtil.sha256(originalValue.toString()).substring(0, 16);
    }
}
