package com.apishield.dp.noise.impl;

import com.apishield.dp.noise.NoiseGenerator;
import org.springframework.stereotype.Component;

import java.util.Random;

@Component
public class GaussianNoiseGenerator implements NoiseGenerator {

    private static final Random RANDOM = new Random();

    @Override
    public String getNoiseType() {
        return "GAUSSIAN";
    }

    @Override
    public double generateNoise(double sensitivity, double epsilon, double delta) {
        if (epsilon >= 1) {
            throw new IllegalArgumentException("Gaussian mechanism requires epsilon < 1");
        }
        double sigma = sensitivity * Math.sqrt(2 * Math.log(1.25 / delta)) / epsilon;
        return RANDOM.nextGaussian() * sigma;
    }

    @Override
    public double addNoise(double value, double sensitivity, double epsilon, double delta) {
        return value + generateNoise(sensitivity, epsilon, delta);
    }
}
