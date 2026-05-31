package com.apishield.masking.strategy.impl;

import com.apishield.masking.domain.MaskingPolicy;
import com.apishield.masking.strategy.MaskingStrategy;
import org.springframework.stereotype.Component;

@Component
public class PartialMaskStrategy implements MaskingStrategy {

    @Override
    public MaskingPolicy.MaskingStrategyType getStrategyType() {
        return MaskingPolicy.MaskingStrategyType.PARTIAL_MASK;
    }

    @Override
    public Object mask(Object originalValue, MaskingPolicy policy) {
        if (originalValue == null) {
            return null;
        }
        String str = originalValue.toString();
        if (str.length() <= 4) {
            return "*".repeat(str.length());
        }
        int visibleStart = Math.min(2, str.length() / 4);
        int visibleEnd = Math.min(2, str.length() / 4);
        String prefix = str.substring(0, visibleStart);
        String suffix = str.substring(str.length() - visibleEnd);
        String middle = "*".repeat(str.length() - visibleStart - visibleEnd);
        return prefix + middle + suffix;
    }
}
