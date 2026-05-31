package com.datamasker.testdata;

import com.datamasker.domain.privacy.model.NoisyResult;
import com.datamasker.domain.privacy.model.PrivacyBudget;
import com.datamasker.domain.privacy.model.Sensitivity;

import java.time.LocalDateTime;

public class PrivacyTestDataMother {

    public static final double DEFAULT_EPSILON = 1.0;
    public static final double DEFAULT_DELTA = 1.0E-5;
    public static final double DEFAULT_SENSITIVITY = 1.0;
    public static final double DEFAULT_VALUE = 100.0;
    public static final String DEFAULT_QUERY_ID = "query-001";
    public static final String LAPLACE_MECHANISM = "LAPLACE";
    public static final String GAUSSIAN_MECHANISM = "GAUSSIAN";

    public static NoisyResult noisyResult() {
        NoisyResult result = new NoisyResult();
        result.setOriginalValue(DEFAULT_VALUE);
        result.setNoiseAdded(5.0);
        result.setNoisyValue(DEFAULT_VALUE + 5.0);
        result.setEpsilon(DEFAULT_EPSILON);
        result.setMechanism(LAPLACE_MECHANISM);
        result.setQueryId(DEFAULT_QUERY_ID);
        return result;
    }

    public static NoisyResult noisyResult(double value, String mechanism) {
        NoisyResult result = new NoisyResult();
        result.setOriginalValue(value);
        result.setNoiseAdded(Math.random() * 10);
        result.setNoisyValue(value + result.getNoiseAdded());
        result.setEpsilon(DEFAULT_EPSILON);
        result.setMechanism(mechanism);
        result.setQueryId("query-" + System.currentTimeMillis());
        return result;
    }

    public static PrivacyBudget privacyBudget() {
        PrivacyBudget budget = new PrivacyBudget();
        budget.setQueryId("budget-001");
        budget.setEpsilonConsumed(0.5);
        budget.setDeltaConsumed(0.0);
        budget.setTotalBudget(10.0);
        budget.setRemainingBudget(9.5);
        budget.setCreatedAt(LocalDateTime.now());
        return budget;
    }

    public static PrivacyBudget privacyBudget(double remaining) {
        PrivacyBudget budget = privacyBudget();
        budget.setRemainingBudget(remaining);
        budget.setEpsilonConsumed(10.0 - remaining);
        return budget;
    }

    public static Sensitivity sensitivity() {
        Sensitivity sensitivity = new Sensitivity();
        sensitivity.setQueryType("COUNT");
        sensitivity.setGlobalSensitivity(1.0);
        sensitivity.setLocalSensitivity(0.5);
        sensitivity.setComputedAt(LocalDateTime.now());
        return sensitivity;
    }

    public static class PrivacyScenario {
        public static final double[] TEST_VALUES = {0.0, 1.0, 50.0, 100.0, 1000.0, -50.0};
        public static final double[] TEST_SENSITIVITIES = {0.1, 1.0, 5.0, 10.0};
        public static final double[] TEST_EPSILONS = {0.1, 0.5, 1.0, 2.0, 5.0};
    }

    public static NoisyResultBuilder noisyResultBuilder() {
        return new NoisyResultBuilder();
    }

    public static class NoisyResultBuilder {
        private double originalValue = DEFAULT_VALUE;
        private double noiseAdded = 5.0;
        private double epsilon = DEFAULT_EPSILON;
        private String mechanism = LAPLACE_MECHANISM;
        private String queryId = DEFAULT_QUERY_ID;

        public NoisyResultBuilder withOriginalValue(double value) {
            this.originalValue = value;
            return this;
        }

        public NoisyResultBuilder withEpsilon(double epsilon) {
            this.epsilon = epsilon;
            return this;
        }

        public NoisyResultBuilder withMechanism(String mechanism) {
            this.mechanism = mechanism;
            return this;
        }

        public NoisyResult build() {
            NoisyResult result = new NoisyResult();
            result.setOriginalValue(originalValue);
            result.setNoiseAdded(noiseAdded);
            result.setNoisyValue(originalValue + noiseAdded);
            result.setEpsilon(epsilon);
            result.setMechanism(mechanism);
            result.setQueryId(queryId);
            return result;
        }
    }
}
