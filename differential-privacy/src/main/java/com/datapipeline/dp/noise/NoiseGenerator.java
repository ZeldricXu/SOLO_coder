package com.datapipeline.dp.noise;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.distribution.LaplaceDistribution;
import org.apache.commons.math3.distribution.NormalDistribution;

import java.security.SecureRandom;

@Slf4j
public class NoiseGenerator {

    public enum Type {
        LAPLACE,
        GAUSSIAN
    }

    private final SecureRandom secureRandom;

    public NoiseGenerator() {
        this.secureRandom = new SecureRandom();
    }

    public double addLaplaceNoise(double value, double epsilon, double sensitivity) {
        if (epsilon <= 0) {
            log.warn("Epsilon must be positive, returning original value");
            return value;
        }
        double scale = sensitivity / epsilon;
        double noise = sampleLaplace(scale);
        double noisyValue = value + noise;
        log.debug("Laplace noise added: original={}, noise={}, noisy={}, epsilon={}, sensitivity={}",
                value, noise, noisyValue, epsilon, sensitivity);
        return noisyValue;
    }

    public double addGaussianNoise(double value, double epsilon, double delta, double sensitivity) {
        if (epsilon <= 0 || delta <= 0) {
            log.warn("Epsilon and delta must be positive, returning original value");
            return value;
        }
        double sigma = calculateGaussianSigma(epsilon, delta, sensitivity);
        double noise = sampleGaussian(sigma);
        double noisyValue = value + noise;
        log.debug("Gaussian noise added: original={}, noise={}, noisy={}, epsilon={}, delta={}, sensitivity={}",
                value, noise, noisyValue, epsilon, delta, sensitivity);
        return noisyValue;
    }

    public long addLaplaceNoise(long value, double epsilon, double sensitivity) {
        return Math.round(addLaplaceNoise((double) value, epsilon, sensitivity));
    }

    public long addGaussianNoise(long value, double epsilon, double delta, double sensitivity) {
        return Math.round(addGaussianNoise((double) value, epsilon, delta, sensitivity));
    }

    public int addLaplaceNoise(int value, double epsilon, double sensitivity) {
        return (int) Math.round(addLaplaceNoise((double) value, epsilon, sensitivity));
    }

    public int addGaussianNoise(int value, double epsilon, double delta, double sensitivity) {
        return (int) Math.round(addGaussianNoise((double) value, epsilon, delta, sensitivity));
    }

    private double sampleLaplace(double scale) {
        LaplaceDistribution laplace = new LaplaceDistribution(secureRandom, 0, scale);
        return laplace.sample();
    }

    private double sampleGaussian(double sigma) {
        NormalDistribution normal = new NormalDistribution(secureRandom, 0, sigma);
        return normal.sample();
    }

    private double calculateGaussianSigma(double epsilon, double delta, double sensitivity) {
        if (epsilon >= 1) {
            return sensitivity * Math.sqrt(2 * Math.log(1.25 / delta)) / epsilon;
        }
        return sensitivity * Math.sqrt(2 * Math.log(1.25 / delta) / epsilon);
    }

}
