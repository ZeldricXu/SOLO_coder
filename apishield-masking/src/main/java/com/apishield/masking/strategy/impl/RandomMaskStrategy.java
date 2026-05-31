package com.apishield.masking.strategy.impl;

import com.apishield.masking.domain.MaskingPolicy;
import com.apishield.masking.strategy.MaskingStrategy;
import org.springframework.stereotype.Component;

import java.util.Random;

@Component
public class RandomMaskStrategy implements MaskingStrategy {

    private static final Random RANDOM = new Random();
    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    @Override
    public MaskingPolicy.MaskingStrategyType getStrategyType() {
        return MaskingPolicy.MaskingStrategyType.RANDOM;
    }

    @Override
    public Object mask(Object originalValue, MaskingPolicy policy) {
        if (originalValue == null) {
            return null;
        }
        int length = originalValue.toString().length();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }
}
