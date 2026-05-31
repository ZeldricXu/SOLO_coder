package com.datamasker.domain.privacy.mechanism;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ExponentialMechanism {

    public String select(List<String> candidates, List<Double> scores, double sensitivity, double epsilon) {
        List<Double> probabilities = computeProbabilities(scores, sensitivity, epsilon);
        double rand = Math.random();
        double cumulative = 0.0;
        for (int i = 0; i < probabilities.size(); i++) {
            cumulative += probabilities.get(i);
            if (rand <= cumulative) {
                return candidates.get(i);
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    public List<Double> computeProbabilities(List<Double> scores, double sensitivity, double epsilon) {
        List<Double> expScores = new ArrayList<>();
        double maxScore = scores.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        double sum = 0.0;
        for (Double score : scores) {
            double expVal = Math.exp(epsilon * (score - maxScore) / (2 * sensitivity));
            expScores.add(expVal);
            sum += expVal;
        }
        List<Double> probabilities = new ArrayList<>();
        for (Double expVal : expScores) {
            probabilities.add(expVal / sum);
        }
        return probabilities;
    }
}
