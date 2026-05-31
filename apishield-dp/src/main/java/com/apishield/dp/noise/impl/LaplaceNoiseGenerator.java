package com.apishield.dp.noise.impl;

import com.apishield.dp.noise.NoiseGenerator;
import org.springframework.stereotype.Component;

import java.util.Random;

@Component
public class LaplaceNoiseGenerator implements NoiseGenerator {

    private static final Random RANDOM = new Random();

    @Override
    public String getNoiseType() {
        return "LAPLACE";
    }

    @Override
    public double generateNoise(double sensitivity, double epsilon, double delta) {
        double scale = sensitivity / epsilon;
        double u = RANDOM.nextDouble() - 0.5;
        return -scale * Math.signum(u) * Math.log(1 - 2 * Math.abs(u));
    }

    @Override
    public double addNoise(double value, double sensitivity, double epsilon, double delta) {
        return value + generateNoise(sensitivity, epsilon, delta);
    }
}
