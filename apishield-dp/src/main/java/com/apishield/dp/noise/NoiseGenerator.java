package com.apishield.dp.noise;

public interface NoiseGenerator {
    String getNoiseType();
    double generateNoise(double sensitivity, double epsilon, double delta);
    double addNoise(double value, double sensitivity, double epsilon, double delta);
}
