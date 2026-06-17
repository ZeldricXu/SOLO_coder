package com.enterprise.risk.engine.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 规则结果加权融合器
 * 将多个规则命中结果按优先级/权重融合成综合规则分
 * 供后续模型分加权使用
 *
 * 融合策略：
 * 1. 简单加权平均（默认）
 * 2. 优先级加权：高优先级规则权重更高
 * 3. 最大值保留：取所有匹配规则中的最高分
 * 4. 非线性融合：使用sigmoid或对数平滑，避免线性叠加
 */
@Component
public class RuleWeightedFusion {

    private static final Logger log = LoggerFactory.getLogger(RuleWeightedFusion.class);

    /**
     * 融合策略
     */
    public enum FusionStrategy {
        WEIGHTED_AVERAGE,
        PRIORITY_WEIGHTED,
        MAX_SCORE,
        NON_LINEAR
    }

    private final FusionStrategy strategy;
    private final double priorityWeightFactor;
    private final double nonlinearFactor;
    private final double minScoreThreshold;

    public RuleWeightedFusion(
            @Value("${risk.engine.fusion.strategy:WEIGHTED_AVERAGE}") FusionStrategy strategy,
            @Value("${risk.engine.fusion.priority-factor:2.0}") double priorityWeightFactor,
            @Value("${risk.engine.fusion.nonlinear-factor:1.5}") double nonlinearFactor,
            @Value("${risk.engine.fusion.min-threshold:0.0}") double minScoreThreshold) {
        this.strategy = strategy != null ? strategy : FusionStrategy.WEIGHTED_AVERAGE;
        this.priorityWeightFactor = priorityWeightFactor;
        this.nonlinearFactor = nonlinearFactor;
        this.minScoreThreshold = minScoreThreshold;
        log.info("规则加权融合器初始化: strategy={}, priorityFactor={}, nonlinearFactor={}",
                this.strategy, this.priorityWeightFactor, this.nonlinearFactor);
    }

    /**
     * 单个加权结果
     */
    public record WeightedResult(
            String ruleId,
            double score,
            double weight
    ) {}

    /**
     * 融合多个规则结果，返回综合分数 [0, 1]
     */
    public double fuse(List<WeightedResult> results) {
        if (results == null || results.isEmpty()) {
            return 0.0;
        }

        List<WeightedResult> filtered = results.stream()
                .filter(r -> r.score() >= minScoreThreshold)
                .toList();

        if (filtered.isEmpty()) {
            return 0.0;
        }

        double finalScore = switch (strategy) {
            case WEIGHTED_AVERAGE -> weightedAverage(filtered);
            case PRIORITY_WEIGHTED -> priorityWeighted(filtered);
            case MAX_SCORE -> maxScore(filtered);
            case NON_LINEAR -> nonLinear(filtered);
        };

        finalScore = Math.min(1.0, Math.max(0.0, finalScore));

        if (log.isDebugEnabled()) {
            log.debug("规则融合结果: strategy={}, inputCount={}, finalScore={}",
                    strategy, results.size(), String.format("%.4f", finalScore));
        }

        return finalScore;
    }

    /**
     * 简单加权平均
     * final = Σ(score_i * weight_i) / Σ(weight_i)
     */
    private double weightedAverage(List<WeightedResult> results) {
        double weightedSum = 0.0;
        double weightSum = 0.0;
        for (WeightedResult r : results) {
            double w = Math.max(0.01, r.weight());
            weightedSum += r.score() * w;
            weightSum += w;
        }
        if (weightSum <= 0) {
            return 0.0;
        }
        return weightedSum / weightSum;
    }

    /**
     * 优先级加权
     * 按输入顺序视为优先级降序（第一个优先级最高）
     * 权重 = weight_i * factor^(n - index - 1)
     */
    private double priorityWeighted(List<WeightedResult> results) {
        double weightedSum = 0.0;
        double weightSum = 0.0;
        int n = results.size();
        for (int i = 0; i < n; i++) {
            WeightedResult r = results.get(i);
            double baseWeight = Math.max(0.01, r.weight());
            double priorityBoost = Math.pow(priorityWeightFactor, n - i - 1);
            double effectiveWeight = baseWeight * priorityBoost;
            weightedSum += r.score() * effectiveWeight;
            weightSum += effectiveWeight;
        }
        if (weightSum <= 0) {
            return 0.0;
        }
        return weightedSum / weightSum;
    }

    /**
     * 最大值保留策略
     */
    private double maxScore(List<WeightedResult> results) {
        double max = 0.0;
        for (WeightedResult r : results) {
            double effective = r.score() * Math.min(1.0, r.weight());
            if (effective > max) {
                max = effective;
            }
        }
        return max;
    }

    /**
     * 非线性融合
     * 使用 sigmoid-like 函数避免线性叠加导致的分数过高
     * final = 1 - Π(1 - score_i^k)^(w_i)
     */
    private double nonLinear(List<WeightedResult> results) {
        double product = 1.0;
        double totalWeight = 0.0;
        for (WeightedResult r : results) {
            double w = Math.max(0.01, r.weight());
            double s = Math.pow(Math.min(1.0, r.score()), nonlinearFactor);
            double term = Math.pow(1.0 - s, w);
            product *= term;
            totalWeight += w;
        }
        if (totalWeight <= 0) {
            return 0.0;
        }
        double raw = 1.0 - product;
        return smoothSigmoid(raw);
    }

    /**
     * sigmoid平滑函数，将[0,1]映射到更平滑的[0,1]
     */
    private double smoothSigmoid(double x) {
        double k = 8.0;
        double sig = 1.0 / (1.0 + Math.exp(-k * (x - 0.5)));
        double min = 1.0 / (1.0 + Math.exp(k * 0.5));
        double max = 1.0 / (1.0 + Math.exp(-k * 0.5));
        return (sig - min) / (max - min);
    }

    /**
     * 融合后计算规则分和模型分的综合分
     *
     * @param ruleScore   规则融合分 [0,1]
     * @param modelScore  模型分 [0,1]
     * @param ruleWeight  规则权重 [0,1]，modelWeight = 1 - ruleWeight
     * @return 综合分 [0,1]
     */
    public double fuseRuleAndModel(double ruleScore, double modelScore, double ruleWeight) {
        ruleScore = Math.min(1.0, Math.max(0.0, ruleScore));
        modelScore = Math.min(1.0, Math.max(0.0, modelScore));
        ruleWeight = Math.min(1.0, Math.max(0.0, ruleWeight));
        double modelWeight = 1.0 - ruleWeight;
        double combined = ruleScore * ruleWeight + modelScore * modelWeight;
        return Math.min(1.0, Math.max(0.0, combined));
    }

    /**
     * 获取当前策略
     */
    public FusionStrategy getStrategy() {
        return strategy;
    }
}
