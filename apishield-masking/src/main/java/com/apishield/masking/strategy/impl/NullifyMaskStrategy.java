package com.apishield.masking.strategy.impl;

import com.apishield.masking.domain.MaskingPolicy;
import com.apishield.masking.strategy.MaskingStrategy;
import org.springframework.stereotype.Component;

@Component
public class NullifyMaskStrategy implements MaskingStrategy {

    @Override
    public MaskingPolicy.MaskingStrategyType getStrategyType() {
        return MaskingPolicy.MaskingStrategyType.NULLIFY;
    }

    @Override
    public Object mask(Object originalValue, MaskingPolicy policy) {
        return null;
    }
}
