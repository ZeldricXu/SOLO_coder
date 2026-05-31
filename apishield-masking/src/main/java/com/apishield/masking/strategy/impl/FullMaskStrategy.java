package com.apishield.masking.strategy.impl;

import com.apishield.masking.domain.MaskingPolicy;
import com.apishield.masking.strategy.MaskingStrategy;
import org.springframework.stereotype.Component;

@Component
public class FullMaskStrategy implements MaskingStrategy {

    @Override
    public MaskingPolicy.MaskingStrategyType getStrategyType() {
        return MaskingPolicy.MaskingStrategyType.FULL_MASK;
    }

    @Override
    public Object mask(Object originalValue, MaskingPolicy policy) {
        if (originalValue == null) {
            return null;
        }
        String str = originalValue.toString();
        return "*".repeat(Math.min(str.length(), 10));
    }
}
