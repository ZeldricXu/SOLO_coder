package com.delivery.tracker.privacy.impl;

import com.delivery.tracker.privacy.PrivacyConfig;
import com.delivery.tracker.privacy.PrivacyStrategy;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 空操作隐私策略
 * 不添加任何噪声，用于调试或低敏感场景
 */
@Component("NO_OP")
public class NoOpPrivacyStrategy implements PrivacyStrategy {

    private static final String NAME = "NO_OP";
    private static final Set<String> SUPPORTED_SCENES = Set.of(
            "DEBUG",
            "LOW_SENSITIVITY",
            "INTERNAL_USE"
    );

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "空操作隐私策略，不添加任何噪声，用于调试或低敏感场景";
    }

    @Override
    public Map<String, Object> applyPrivacy(Map<String, Object> queryResult, PrivacyConfig config) {
        if (queryResult == null) {
            return new HashMap<>();
        }
        return new HashMap<>(queryResult);
    }

    @Override
    public double calculateEpsilonCost(PrivacyConfig config) {
        return 0.0;
    }

    @Override
    public boolean supportsScene(String scene) {
        return SUPPORTED_SCENES.contains(scene);
    }
}
