package com.apishield.dp.noise.impl;

import com.apishield.dp.noise.NoiseGenerator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Random;

@Component
public class ExponentialNoiseGenerator implements NoiseGenerator {

    private static final Random RANDOM = new Random();

    @Override
    public String getNoiseType() {
        return "EXPONENTIAL";
    }

    @Override
    public double generateNoise(double sensitivity, double epsilon, double delta) {
        double scale = epsilon / (2 * sensitivity);
        double u = RANDOM.nextDouble();
        return -Math.log(u) / scale;
    }

    @Override
    public double addNoise(double value, double sensitivity, double epsilon, double delta) {
        return value + generateNoise(sensitivity, epsilon, delta);
    }

    public <T> T selectFromList(List<T> items, java.util.function.ToDoubleFunction<T> scoreFunction, 
                                 double sensitivity, double epsilon) {
        double[] weights = items.stream()
                .mapToDouble(item -> Math.exp(epsilon * scoreFunction.applyAsDouble(item) / (2 * sensitivity)))
                .toArray();
        
        double totalWeight = 0;
        for (double w : weights) {
            totalWeight += w;
        }
        
        double random = RANDOM.nextDouble() * totalWeight;
        double cumulative = 0;
        
        for (int i = 0; i < items.size(); i++) {
            cumulative += weights[i];
            if (random <= cumulative) {
                return items.get(i);
            }
        }
        
        return items.get(items.size() - 1);
    }
}
