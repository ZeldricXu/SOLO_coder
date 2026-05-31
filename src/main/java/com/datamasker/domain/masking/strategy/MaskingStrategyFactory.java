package com.datamasker.domain.masking.strategy;

import com.datamasker.domain.masking.model.MaskingStrategy;
import com.datamasker.infrastructure.config.MaskingConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Random;

@Component
@RequiredArgsConstructor
public class MaskingStrategyFactory {

    private final MaskingConfig maskingConfig;

    private final Random random = new SecureRandom();

    public interface MaskingStrategyExecutor {
        String mask(String value, String params);
    }

    public MaskingStrategyExecutor getStrategy(MaskingStrategy strategy, String params) {
        return switch (strategy) {
            case FULL -> (value, p) -> maskFull(value);
            case PARTIAL -> (value, p) -> maskPartial(value);
            case HASH -> (value, p) -> maskHash(value);
            case RANDOM -> (value, p) -> maskRandom(value);
            case REDACT -> (value, p) -> "[REDACTED]";
            case CUSTOM -> (value, p) -> maskCustom(value, p != null ? p : params);
        };
    }

    private String maskFull(String value) {
        if (!maskingConfig.isPreserveLength()) {
            return "******";
        }
        return "*".repeat(value.length());
    }

    private String maskPartial(String value) {
        if (value.length() < 4) {
            return value.charAt(0) + "*".repeat(value.length() - 1);
        }
        int keepFirst = Math.max(1, value.length() / 3);
        int keepLast = Math.max(1, value.length() / 4);
        int middleLen = value.length() - keepFirst - keepLast;
        if (middleLen <= 0) {
            return value.charAt(0) + "*".repeat(value.length() - 1);
        }
        return value.substring(0, keepFirst) + "*".repeat(middleLen) + value.substring(value.length() - keepLast);
    }

    private String maskHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                String hex = Integer.toHexString(0xff & hash[i]);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String maskRandom(String value) {
        StringBuilder result = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (Character.isDigit(c)) {
                result.append((char) ('0' + random.nextInt(10)));
            } else if (Character.isLetter(c)) {
                if (Character.isUpperCase(c)) {
                    result.append((char) ('A' + random.nextInt(26)));
                } else {
                    result.append((char) ('a' + random.nextInt(26)));
                }
            } else {
                result.append('*');
            }
        }
        return result.toString();
    }

    private String maskCustom(String value, String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return "***" + value + "***";
        }
        return pattern.replace("{v}", value);
    }
}
