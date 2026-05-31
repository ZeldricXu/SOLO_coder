package com.apishield.masking.strategy;

import com.apishield.masking.domain.MaskingPolicy;

public interface MaskingStrategy {
    MaskingPolicy.MaskingStrategyType getStrategyType();
    Object mask(Object originalValue, MaskingPolicy policy);
}
