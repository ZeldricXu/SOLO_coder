package com.hotelbooking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "hotelbooking.fee.validation")
public class FeeValidationConfig {

    private Map<String, FeeTypeConfig> types = new HashMap<>();

    private double tolerance = 0.01;

    public Map<String, FeeTypeConfig> getTypes() {
        return types;
    }

    public void setTypes(Map<String, FeeTypeConfig> types) {
        this.types = types;
    }

    public double getTolerance() {
        return tolerance;
    }

    public void setTolerance(double tolerance) {
        this.tolerance = tolerance;
    }

    public FeeTypeConfig getConfig(String feeType) {
        return types.getOrDefault(feeType.toUpperCase(), new FeeTypeConfig());
    }

    public static class FeeTypeConfig {
        private String name;
        private String description;
        private boolean enabled = true;
        private String validationType;
        private List<String> rules = new ArrayList<>();
        private Map<String, String> parameters = new HashMap<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getValidationType() {
            return validationType;
        }

        public void setValidationType(String validationType) {
            this.validationType = validationType;
        }

        public List<String> getRules() {
            return rules;
        }

        public void setRules(List<String> rules) {
            this.rules = rules;
        }

        public Map<String, String> getParameters() {
            return parameters;
        }

        public void setParameters(Map<String, String> parameters) {
            this.parameters = parameters;
        }

        public String getParameter(String key) {
            return parameters.get(key);
        }
    }
}
