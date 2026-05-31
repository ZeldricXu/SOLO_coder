package com.datamasker.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datamasker.domain.privacy.mechanism.ExponentialMechanism;
import com.datamasker.domain.privacy.mechanism.GaussianMechanism;
import com.datamasker.domain.privacy.mechanism.LaplaceMechanism;
import com.datamasker.domain.privacy.model.NoisyResult;
import com.datamasker.infrastructure.config.PrivacyConfig;
import com.datamasker.infrastructure.persistence.entity.PrivacyBudgetEntity;
import com.datamasker.infrastructure.persistence.mapper.PrivacyBudgetMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DifferentialPrivacyService {

    private final LaplaceMechanism laplaceMechanism;
    private final GaussianMechanism gaussianMechanism;
    private final ExponentialMechanism exponentialMechanism;
    private final PrivacyConfig privacyConfig;
    private final PrivacyBudgetMapper privacyBudgetMapper;

    public NoisyResult addNoiseToQuery(double value, double sensitivity, String mechanismType) {
        double epsilon = privacyConfig.getDefaultEpsilon();
        double delta = privacyConfig.getDefaultDelta();
        return addNoiseToQuery(value, sensitivity, epsilon, delta, mechanismType);
    }

    public NoisyResult addNoiseToQuery(double value, double sensitivity, double epsilon, double delta, String mechanismType) {
        double remaining = getRemainingBudget();
        if (remaining < epsilon) {
            throw new IllegalStateException("Insufficient privacy budget: remaining=" + remaining + ", required=" + epsilon);
        }
        NoisyResult result;
        String queryId = UUID.randomUUID().toString();
        switch (mechanismType.toUpperCase()) {
            case "GAUSSIAN":
                result = gaussianMechanism.addNoise(value, sensitivity, epsilon, delta);
                break;
            case "EXPONENTIAL":
                result = new NoisyResult();
                result.setOriginalValue(value);
                result.setNoiseAdded(0.0);
                result.setNoisyValue(value);
                result.setEpsilon(epsilon);
                result.setMechanism("EXPONENTIAL");
                result.setQueryId(queryId);
                break;
            default:
                result = laplaceMechanism.addNoise(value, sensitivity, epsilon);
                break;
        }
        result.setQueryId(queryId);
        consumeBudget(queryId, epsilon, mechanismType.equalsIgnoreCase("GAUSSIAN") ? delta : 0.0);
        return result;
    }

    public double getRemainingBudget() {
        LambdaQueryWrapper<PrivacyBudgetEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(PrivacyBudgetEntity::getCreatedAt);
        PrivacyBudgetEntity latest = privacyBudgetMapper.selectOne(wrapper, false);
        if (latest == null) {
            return privacyConfig.getMaxBudget();
        }
        return latest.getRemainingBudget();
    }

    public void consumeBudget(String queryId, double epsilon, double delta) {
        double remaining = getRemainingBudget();
        if (remaining < epsilon) {
            throw new IllegalStateException("Insufficient privacy budget: remaining=" + remaining + ", required=" + epsilon);
        }
        PrivacyBudgetEntity budget = new PrivacyBudgetEntity();
        budget.setQueryId(queryId);
        budget.setEpsilonConsumed(epsilon);
        budget.setDeltaConsumed(delta);
        budget.setTotalBudget(privacyConfig.getMaxBudget());
        budget.setRemainingBudget(remaining - epsilon);
        budget.setCreatedAt(LocalDateTime.now());
        privacyBudgetMapper.insert(budget);
    }

    public void resetBudget() {
        PrivacyBudgetEntity budget = new PrivacyBudgetEntity();
        budget.setQueryId("RESET");
        budget.setEpsilonConsumed(0.0);
        budget.setDeltaConsumed(0.0);
        budget.setTotalBudget(privacyConfig.getMaxBudget());
        budget.setRemainingBudget(privacyConfig.getMaxBudget());
        budget.setCreatedAt(LocalDateTime.now());
        privacyBudgetMapper.insert(budget);
    }
}
