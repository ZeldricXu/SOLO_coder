package com.delivery.tracker.privacy.impl;

import com.delivery.tracker.privacy.PrivacyConfig;
import com.delivery.tracker.privacy.PrivacyStrategy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.distribution.LaplaceDistribution;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Laplace机制隐私策略
 * 适用于计数、求和等数值型查询
 */
@Slf4j
@Component("LAPLACE")
public class LaplacePrivacyStrategy implements PrivacyStrategy {

    private static final String NAME = "LAPLACE";
    private static final Set<String> SUPPORTED_SCENES = Set.of(
            "COUNT_QUERY",
            "SUM_QUERY",
            "AVG_QUERY",
            "DEFAULT"
    );

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Laplace机制隐私策略，适用于计数、求和等数值型查询，提供严格的差分隐私保护";
    }

    @Override
    public Map<String, Object> applyPrivacy(Map<String, Object> queryResult, PrivacyConfig config) {
        if (queryResult == null || queryResult.isEmpty()) {
            return new HashMap<>();
        }

        Map<String, Object> protectedResult = new HashMap<>(queryResult);
        double scale = config.getSensitivity() / config.getEpsilon() * config.getScaleFactor();

        LaplaceDistribution laplace = new LaplaceDistribution(secureRandom, 0, scale);

        protectedResult.forEach((key, value) -> {
            if (value instanceof Number) {
                double noise = laplace.sample();
                protectedResult.put(key, addNoise(value, noise));
            }
        });

        log.debug("Laplace策略应用完成, scale={}, epsilon={}", scale, config.getEpsilon());
        return protectedResult;
    }

    private Object addNoise(Object value, double noise) {
        if (value instanceof Integer) {
            return ((Integer) value) + (int) Math.round(noise);
        } else if (value instanceof Long) {
            return ((Long) value) + Math.round(noise);
        } else if (value instanceof Double) {
            return ((Double) value) + noise;
        } else if (value instanceof Float) {
            return ((Float) value) + (float) noise;
        } else if (value instanceof BigDecimal) {
            return ((BigDecimal) value).add(BigDecimal.valueOf(noise));
        }
        return value;
    }

    @Override
    public double calculateEpsilonCost(PrivacyConfig config) {
        return config.getEpsilon();
    }

    @Override
    public boolean supportsScene(String scene) {
        return SUPPORTED_SCENES.contains(scene) || "DEFAULT".equals(scene);
    }
}
