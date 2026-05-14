package com.learningplatform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "learning.certificate")
public class CertificateConfig {

    private Map<String, String> algorithm = new HashMap<>();
    private Map<String, Integer> validityYears = new HashMap<>();
    private String secret = "LEARNING_PLATFORM_SECRET_KEY_2024";

    public String getAlgorithmByType(String certificateType) {
        return algorithm.getOrDefault(certificateType, "HMAC-SHA256");
    }

    public int getValidityYearsByType(String certificateType) {
        return validityYears.getOrDefault(certificateType, 3);
    }

    public boolean isStrongAlgorithm(String algorithm) {
        return "HMAC-SHA512".equals(algorithm);
    }

    public boolean isStrongSignatureType(String certificateType) {
        return isStrongAlgorithm(getAlgorithmByType(certificateType));
    }

    public Map<String, String> getAvailableAlgorithms() {
        Map<String, String> result = new HashMap<>(algorithm);
        if (!result.containsKey("default")) {
            result.put("default", "HMAC-SHA256");
        }
        return result;
    }

    public void updateAlgorithm(String certificateType, String algorithmValue) {
        this.algorithm.put(certificateType, algorithmValue);
    }

    public void updateValidityYears(String certificateType, int years) {
        this.validityYears.put(certificateType, years);
    }

    public void removeCertificateType(String certificateType) {
        this.algorithm.remove(certificateType);
        this.validityYears.remove(certificateType);
    }
}
