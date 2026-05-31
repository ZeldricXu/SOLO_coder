package com.delivery.tracker.privacy.impl;

import com.delivery.tracker.privacy.PrivacyConfig;
import com.delivery.tracker.privacy.PrivacyStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.security.SecureRandom;
import java.util.*;

/**
 * Exponential机制隐私策略
 * 适用于分类数据、选择查询等非数值型场景
 */
@Slf4j
@Component("EXPONENTIAL")
public class ExponentialPrivacyStrategy implements PrivacyStrategy {

    private static final String NAME = "EXPONENTIAL";
    private static final Set<String> SUPPORTED_SCENES = Set.of(
            "CATEGORICAL_QUERY",
            "SELECTION_QUERY",
            "RANKING_QUERY"
    );

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Exponential机制隐私策略，适用于分类数据、选择查询等非数值型场景";
    }

    @Override
    public Map<String, Object> applyPrivacy(Map<String, Object> queryResult, PrivacyConfig config) {
        if (queryResult == null || queryResult.isEmpty()) {
            return new HashMap<>();
        }

        Map<String, Object> protectedResult = new HashMap<>(queryResult);

        protectedResult.forEach((key, value) -> {
            if (value instanceof String) {
                protectedResult.put(key, addNoiseToString((String) value, config));
            } else if (value instanceof Boolean) {
                protectedResult.put(key, flipBoolean((Boolean) value, config));
            }
        });

        log.debug("Exponential策略应用完成, epsilon={}", config.getEpsilon());
        return protectedResult;
    }

    private String addNoiseToString(String value, PrivacyConfig config) {
        if (value == null || value.isEmpty()) {
            return value;
        }

        double epsilon = config.getEpsilon();
        double probability = 1.0 / (1.0 + Math.exp(-epsilon));

        if (secureRandom.nextDouble() > probability) {
            char[] chars = value.toCharArray();
            int index = secureRandom.nextInt(chars.length);
            chars[index] = (char) (chars[index] + secureRandom.nextInt(26) - 13);
            return new String(chars);
        }
        return value;
    }

    private Boolean flipBoolean(Boolean value, PrivacyConfig config) {
        double epsilon = config.getEpsilon();
        double flipProbability = 1.0 / (1.0 + Math.exp(epsilon));
        if (secureRandom.nextDouble() < flipProbability) {
            return !value;
        }
        return value;
    }

    @Override
    public double calculateEpsilonCost(PrivacyConfig config) {
        return config.getEpsilon();
    }

    @Override
    public boolean supportsScene(String scene) {
        return SUPPORTED_SCENES.contains(scene);
    }
}
