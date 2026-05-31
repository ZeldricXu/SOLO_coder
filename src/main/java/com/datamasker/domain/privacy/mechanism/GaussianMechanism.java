package com.datamasker.domain.privacy.mechanism;

import com.datamasker.domain.privacy.model.NoisyResult;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class GaussianMechanism {

    public NoisyResult addNoise(double value, double sensitivity, double epsilon, double delta) {
        double sigma = computeSigma(sensitivity, epsilon, delta);
        double noise = generateGaussian(sigma);
        NoisyResult result = new NoisyResult();
        result.setOriginalValue(value);
        result.setNoiseAdded(noise);
        result.setNoisyValue(value + noise);
        result.setEpsilon(epsilon);
        result.setMechanism("GAUSSIAN");
        result.setQueryId(UUID.randomUUID().toString());
        return result;
    }

    public double generateGaussian(double sigma) {
        double u1 = Math.random();
        double u2 = Math.random();
        while (u1 == 0.0) {
            u1 = Math.random();
        }
        return sigma * Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
    }

    public double computeSigma(double sensitivity, double epsilon, double delta) {
        return sensitivity * Math.sqrt(2 * Math.log(1.25 / delta)) / epsilon;
    }
}
