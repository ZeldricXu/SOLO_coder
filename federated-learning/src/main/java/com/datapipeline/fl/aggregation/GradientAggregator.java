package com.datapipeline.fl.aggregation;

import com.datapipeline.fl.model.GlobalModel;
import com.datapipeline.fl.model.LocalGradient;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class GradientAggregator {

    public enum AggregationStrategy {
        FED_AVG,
        WEIGHTED_FED_AVG,
        FED_PROX
    }

    private final AggregationStrategy strategy;

    public GradientAggregator() {
        this(AggregationStrategy.FED_AVG);
    }

    public GradientAggregator(AggregationStrategy strategy) {
        this.strategy = strategy;
        log.info("GradientAggregator initialized with strategy: {}", strategy);
    }

    public GlobalModel aggregate(GlobalModel currentModel, Collection<LocalGradient> gradients) {
        if (gradients.isEmpty()) {
            log.warn("No gradients to aggregate, returning current model");
            return currentModel;
        }

        Map<String, double[]> aggregatedGradients;
        switch (strategy) {
            case WEIGHTED_FED_AVG -> aggregatedGradients = aggregateWeightedFedAvg(gradients, currentModel.getWeights().keySet());
            case FED_PROX -> aggregatedGradients = aggregateFedProx(gradients, currentModel);
            default -> aggregatedGradients = aggregateFedAvg(gradients, currentModel.getWeights().keySet());
        }

        Map<String, double[]> newWeights = applyGradients(currentModel.getWeights(), aggregatedGradients);

        return GlobalModel.builder()
                .modelId(currentModel.getModelId())
                .name(currentModel.getName())
                .version(currentModel.getVersion() + 1)
                .weights(newWeights)
                .metadata(new HashMap<>(currentModel.getMetadata()))
                .createdAt(currentModel.getCreatedAt())
                .updatedAt(java.time.Instant.now())
                .round(currentModel.getRound() + 1)
                .participantCount(gradients.size())
                .build();
    }

    private Map<String, double[]> aggregateFedAvg(Collection<LocalGradient> gradients, Set<String> weightNames) {
        Map<String, double[]> result = new ConcurrentHashMap<>();
        int count = gradients.size();

        for (String name : weightNames) {
            double[] sum = null;
            for (LocalGradient grad : gradients) {
                double[] g = grad.getGradients().get(name);
                if (g == null) continue;
                if (sum == null) {
                    sum = new double[g.length];
                }
                for (int i = 0; i < g.length; i++) {
                    sum[i] += g[i];
                }
            }
            if (sum != null) {
                for (int i = 0; i < sum.length; i++) {
                    sum[i] /= count;
                }
                result.put(name, sum);
            }
        }
        return result;
    }

    private Map<String, double[]> aggregateWeightedFedAvg(Collection<LocalGradient> gradients, Set<String> weightNames) {
        Map<String, double[]> result = new ConcurrentHashMap<>();
        int totalSamples = gradients.stream().mapToInt(LocalGradient::getSampleCount).sum();

        for (String name : weightNames) {
            double[] weightedSum = null;
            for (LocalGradient grad : gradients) {
                double[] g = grad.getGradients().get(name);
                if (g == null) continue;
                if (weightedSum == null) {
                    weightedSum = new double[g.length];
                }
                double weight = (double) grad.getSampleCount() / totalSamples;
                for (int i = 0; i < g.length; i++) {
                    weightedSum[i] += g[i] * weight;
                }
            }
            if (weightedSum != null) {
                result.put(name, weightedSum);
            }
        }
        return result;
    }

    private Map<String, double[]> aggregateFedProx(Collection<LocalGradient> gradients, GlobalModel currentModel) {
        Map<String, double[]> result = aggregateFedAvg(gradients, currentModel.getWeights().keySet());
        double mu = 0.1;

        for (Map.Entry<String, double[]> entry : result.entrySet()) {
            double[] grad = entry.getValue();
            double[] weight = currentModel.getWeights().get(entry.getKey());
            if (weight != null) {
                for (int i = 0; i < grad.length && i < weight.length; i++) {
                    grad[i] += mu * weight[i];
                }
            }
        }
        return result;
    }

    private Map<String, double[]> applyGradients(Map<String, double[]> currentWeights,
                                                 Map<String, double[]> gradients) {
        Map<String, double[]> newWeights = new HashMap<>();
        double learningRate = 0.01;

        for (Map.Entry<String, double[]> entry : currentWeights.entrySet()) {
            double[] current = entry.getValue();
            double[] grad = gradients.get(entry.getKey());
            double[] newWeight = Arrays.copyOf(current, current.length);

            if (grad != null) {
                for (int i = 0; i < newWeight.length && i < grad.length; i++) {
                    newWeight[i] -= learningRate * grad[i];
                }
            }
            newWeights.put(entry.getKey(), newWeight);
        }
        return newWeights;
    }

}
