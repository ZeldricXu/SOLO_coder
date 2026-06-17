package com.enterprise.risk.model;

import com.enterprise.risk.common.model.ModelConfig;
import com.enterprise.risk.common.rule.RuleDefinition;
import com.enterprise.risk.common.rule.RuleEvaluationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ScoreFusionService {

    @Value("${risk.score.default-rule-weight:0.5}")
    private double defaultRuleWeight;

    @Value("${risk.score.default-model-weight:0.5}")
    private double defaultModelWeight;

    @Value("${risk.score.normalize-weights:true}")
    private boolean normalizeWeights;

    private final Map<String, RuleWeights> customWeights = new HashMap<>();

    public double fuse(RuleEvaluationResult result,
                       ModelConfig modelConfig,
                       RuleDefinition ruleDefinition) {
        if (result == null) {
            return 0.0;
        }

        double ruleScore = result.getRuleScore() != null ? result.getRuleScore() : 0.0;
        double modelScore = result.getModelScore() != null ? result.getModelScore() : 0.0;

        double ruleWeight = resolveRuleWeight(ruleDefinition, modelConfig);
        double modelWeight = resolveModelWeight(ruleDefinition, modelConfig);

        double[] normalizedWeights = normalizeIfNeeded(ruleWeight, modelWeight);
        ruleWeight = normalizedWeights[0];
        modelWeight = normalizedWeights[1];

        double finalScore = ruleWeight * ruleScore + modelWeight * modelScore;
        finalScore = clampScore(finalScore);

        if (log.isDebugEnabled()) {
            log.debug("分数融合: ruleScore={}*{}={}, modelScore={}*{}={}, final={}",
                    ruleScore, ruleWeight, ruleWeight * ruleScore,
                    modelScore, modelWeight, modelWeight * modelScore,
                    finalScore);
        }

        result.setFinalScore(finalScore);
        return finalScore;
    }

    public List<RuleEvaluationResult> fuseBatch(List<RuleEvaluationResult> results,
                                                Map<String, ModelConfig> modelConfigMap,
                                                Map<String, RuleDefinition> ruleDefinitionMap) {
        if (results == null || results.isEmpty()) {
            return results;
        }

        for (RuleEvaluationResult result : results) {
            String ruleId = result.getRuleId();
            ModelConfig modelConfig = modelConfigMap != null ? modelConfigMap.get(ruleId) : null;
            RuleDefinition ruleDef = ruleDefinitionMap != null ? ruleDefinitionMap.get(ruleId) : null;
            fuse(result, modelConfig, ruleDef);
        }

        return results;
    }

    public double customFuse(String ruleId, double ruleScore, double modelScore) {
        RuleWeights weights = customWeights.get(ruleId);
        double ruleW = weights != null ? weights.ruleWeight : defaultRuleWeight;
        double modelW = weights != null ? weights.modelWeight : defaultModelWeight;

        double[] normalized = normalizeIfNeeded(ruleW, modelW);
        double finalScore = normalized[0] * ruleScore + normalized[1] * modelScore;
        return clampScore(finalScore);
    }

    public void setCustomWeights(String ruleId, double ruleWeight, double modelWeight) {
        customWeights.put(ruleId, new RuleWeights(ruleWeight, modelWeight));
        log.info("已设置规则 [{}] 的自定义权重: ruleWeight={}, modelWeight={}",
                ruleId, ruleWeight, modelWeight);
    }

    public void removeCustomWeights(String ruleId) {
        RuleWeights removed = customWeights.remove(ruleId);
        if (removed != null) {
            log.info("已移除规则 [{}] 的自定义权重", ruleId);
        }
    }

    public RuleWeights getWeights(String ruleId,
                                  ModelConfig modelConfig,
                                  RuleDefinition ruleDefinition) {
        double ruleWeight = resolveRuleWeight(ruleDefinition, modelConfig);
        double modelWeight = resolveModelWeight(ruleDefinition, modelConfig);
        double[] normalized = normalizeIfNeeded(ruleWeight, modelWeight);
        return new RuleWeights(normalized[0], normalized[1]);
    }

    private double resolveRuleWeight(RuleDefinition ruleDef, ModelConfig modelConfig) {
        if (customWeights.containsKey(ruleDef != null ? ruleDef.getRuleId() : null)) {
            return customWeights.get(ruleDef.getRuleId()).ruleWeight;
        }

        if (ruleDef != null && ruleDef.getModelWeight() != null) {
            double modelW = ruleDef.getModelWeight();
            double ruleW = 1.0 - modelW;
            if (ruleW < 0.0) {
                ruleW = 0.0;
            }
            return ruleW;
        }

        if (modelConfig != null && modelConfig.getWeight() != null) {
            double modelW = modelConfig.getWeight();
            double ruleW = 1.0 - modelW;
            if (ruleW < 0.0) {
                ruleW = 0.0;
            }
            return ruleW;
        }

        return defaultRuleWeight;
    }

    private double resolveModelWeight(RuleDefinition ruleDef, ModelConfig modelConfig) {
        String ruleId = ruleDef != null ? ruleDef.getRuleId() : null;
        if (customWeights.containsKey(ruleId)) {
            return customWeights.get(ruleId).modelWeight;
        }

        if (ruleDef != null && ruleDef.getModelWeight() != null) {
            return clampWeight(ruleDef.getModelWeight());
        }

        if (modelConfig != null && modelConfig.getWeight() != null) {
            return clampWeight(modelConfig.getWeight());
        }

        return defaultModelWeight;
    }

    private double[] normalizeIfNeeded(double ruleWeight, double modelWeight) {
        if (!normalizeWeights) {
            return new double[]{clampWeight(ruleWeight), clampWeight(modelWeight)};
        }

        double sum = ruleWeight + modelWeight;
        if (sum <= 0.0) {
            return new double[]{0.5, 0.5};
        }

        return new double[]{
                ruleWeight / sum,
                modelWeight / sum
        };
    }

    private double clampWeight(double weight) {
        if (weight < 0.0) {
            return 0.0;
        }
        if (weight > 1.0) {
            return 1.0;
        }
        return weight;
    }

    private double clampScore(double score) {
        if (score < 0.0) {
            return 0.0;
        }
        if (score > 1.0) {
            return 1.0;
        }
        return score;
    }

    public double getDefaultRuleWeight() {
        return defaultRuleWeight;
    }

    public double getDefaultModelWeight() {
        return defaultModelWeight;
    }

    public static class RuleWeights {
        public final double ruleWeight;
        public final double modelWeight;

        public RuleWeights(double ruleWeight, double modelWeight) {
            this.ruleWeight = ruleWeight;
            this.modelWeight = modelWeight;
        }

        public double getRuleWeight() {
            return ruleWeight;
        }

        public double getModelWeight() {
            return modelWeight;
        }

        @Override
        public String toString() {
            return String.format("RuleWeights{rule=%.4f, model=%.4f}", ruleWeight, modelWeight);
        }
    }
}
