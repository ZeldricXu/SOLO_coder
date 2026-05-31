package com.datamasker.domain.privacy.mechanism;

import com.datamasker.domain.privacy.model.NoisyResult;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class LaplaceMechanism {

    public NoisyResult addNoise(double value, double sensitivity, double epsilon) {
        double scale = computeScale(sensitivity, epsilon);
        double noise = generateLaplace(scale);
        NoisyResult result = new NoisyResult();
        result.setOriginalValue(value);
        result.setNoiseAdded(noise);
        result.setNoisyValue(value + noise);
        result.setEpsilon(epsilon);
        result.setMechanism("LAPLACE");
        result.setQueryId(UUID.randomUUID().toString());
        return result;
    }

    public double generateLaplace(double scale) {
        double u = Math.random();
        while (u == 0.5) {
            u = Math.random();
        }
        return scale * Math.signum(u - 0.5) * Math.log(1 - 2 * Math.abs(u - 0.5));
    }

    public double computeScale(double sensitivity, double epsilon) {
        return sensitivity / epsilon;
    }
}
