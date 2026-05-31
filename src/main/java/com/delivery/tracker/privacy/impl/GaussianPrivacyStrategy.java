package com.delivery.tracker.privacy.impl;

import com.delivery.tracker.privacy.PrivacyConfig;
import com.delivery.tracker.privacy.PrivacyStrategy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.distribution.NormalDistribution;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Gaussian机制隐私策略
 * 适用于对精度要求较高的场景，提供近似差分隐私保护
 */
@Slf4j
@Component("GAUSSIAN")
public class GaussianPrivacyStrategy implements PrivacyStrategy {

    private static final String NAME = "GAUSSIAN";
    private static final Set<String> SUPPORTED_SCENES = Set.of(
            "STATISTICAL_QUERY",
            "MACHINE_LEARNING",
            "HIGH_PRECISION"
    );

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Gaussian机制隐私策略，适用于对精度要求较高的统计查询和机器学习场景";
    }

    @Override
    public Map<String, Object> applyPrivacy(Map<String, Object> queryResult, PrivacyConfig config) {
        if (queryResult == null || queryResult.isEmpty()) {
            return new HashMap<>();
        }

        Map<String, Object> protectedResult = new HashMap<>(queryResult);

        double sigma = calculateSigma(config);
        NormalDistribution gaussian = new NormalDistribution(secureRandom, 0, sigma);

        protectedResult.forEach((key, value) -> {
            if (value instanceof Number) {
                double noise = gaussian.sample();
                protectedResult.put(key, addNoise(value, noise));
            }
        });

        log.debug("Gaussian策略应用完成, sigma={}, epsilon={}", sigma, config.getEpsilon());
        return protectedResult;
    }

    private double calculateSigma(PrivacyConfig config) {
        double epsilon = config.getEpsilon();
        double delta = config.getDelta();
        double sensitivity = config.getSensitivity();

        return sensitivity * Math.sqrt(2 * Math.log(1.25 / delta)) / epsilon;
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
        return SUPPORTED_SCENES.contains(scene);
    }
}
