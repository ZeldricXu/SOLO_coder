package com.exam.service.sampler;

import com.exam.entity.Question;

import java.util.*;

public class WeightedReservoirSampler {

    private final Random random;

    public WeightedReservoirSampler() {
        this(new Random());
    }

    public WeightedReservoirSampler(Random random) {
        this.random = random;
    }

    public List<Question> sample(List<Question> pool, int k, WeightFunction weightFunction) {
        if (pool == null || pool.isEmpty() || k <= 0) {
            return new ArrayList<>();
        }
        if (k >= pool.size()) {
            return new ArrayList<>(pool);
        }

        Question[] reservoir = new Question[k];
        double[] weights = new double[k];

        int i = 0;
        for (Question q : pool) {
            double w = weightFunction.weight(q);
            double key = Math.pow(random.nextDouble(), 1.0 / w);

            if (i < k) {
                reservoir[i] = q;
                weights[i] = key;
            } else {
                int minIdx = findMinIndex(weights);
                if (key > weights[minIdx]) {
                    reservoir[minIdx] = q;
                    weights[minIdx] = key;
                }
            }
            i++;
        }

        List<Question> result = new ArrayList<>();
        for (Question q : reservoir) {
            if (q != null) {
                result.add(q);
            }
        }
        return result;
    }

    private int findMinIndex(double[] weights) {
        int minIdx = 0;
        double min = weights[0];
        for (int i = 1; i < weights.length; i++) {
            if (weights[i] < min) {
                min = weights[i];
                minIdx = i;
            }
        }
        return minIdx;
    }

    public interface WeightFunction {
        double weight(Question question);
    }
}
