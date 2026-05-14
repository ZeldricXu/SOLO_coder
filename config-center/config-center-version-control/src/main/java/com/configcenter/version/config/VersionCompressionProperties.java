package com.configcenter.version.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "config-center.version-compression")
public class VersionCompressionProperties {
    
    private Boolean enabled = true;
    private Integer maxVersionsPerConfig = 100;
    private Integer compressThresholdVersions = 50;
    private Integer keepLatestVersions = 20;
    private String compressionAlgorithm = "GZIP";
    private Integer minCompressionSize = 1024;
    private Integer batchSize = 100;
    private String scheduleCron = "0 0 2 * * ?";
    
    private CompressionTriggerConfig trigger = new CompressionTriggerConfig();
    
    private RetentionPolicyConfig retention = new RetentionPolicyConfig();
    
    private List<ConfigCompressionPolicy> configPolicies = new ArrayList<>();
    
    private Map<String, Integer> configThresholds = new HashMap<>();
    
    private Map<String, Integer> configKeepVersions = new HashMap<>();
    
    public int getThresholdForConfig(String configId) {
        if (configId != null && configThresholds.containsKey(configId)) {
            return configThresholds.get(configId);
        }
        if (configPolicies != null) {
            for (ConfigCompressionPolicy policy : configPolicies) {
                if (configId != null && policy.getConfigIdPattern() != null &&
                    configId.matches(policy.getConfigIdPattern())) {
                    return policy.getThreshold() != null ? policy.getThreshold() : compressThresholdVersions;
                }
            }
        }
        return compressThresholdVersions;
    }
    
    public int getKeepVersionsForConfig(String configId) {
        if (configId != null && configKeepVersions.containsKey(configId)) {
            return configKeepVersions.get(configId);
        }
        if (configPolicies != null) {
            for (ConfigCompressionPolicy policy : configPolicies) {
                if (configId != null && policy.getConfigIdPattern() != null &&
                    configId.matches(policy.getConfigIdPattern())) {
                    return policy.getKeepVersions() != null ? policy.getKeepVersions() : keepLatestVersions;
                }
            }
        }
        return keepLatestVersions;
    }
    
    @Data
    public static class CompressionTriggerConfig {
        private String mode = "VERSION_COUNT";
        
        private Integer versionCountThreshold = 50;
        
        private Integer timeIntervalHours = 24;
        
        private Integer minVersionsForInterval = 5;
        
        private Boolean combineBoth = false;
    }
    
    @Data
    public static class RetentionPolicyConfig {
        private String mode = "KEEP_LATEST";
        
        private Integer keepLatestN = 20;
        
        private Integer retentionDays = 30;
        
        private List<String> criticalVersionKeywords = new ArrayList<>();
        
        private Boolean keepCriticalVersions = true;
        
        private Integer maxTotalVersions = 200;
    }
    
    @Data
    public static class ConfigCompressionPolicy {
        private String configIdPattern;
        
        private String description;
        
        private Integer threshold;
        
        private Integer keepVersions;
        
        private String compressionAlgorithm;
        
        private Integer minCompressionSize;
        
        private Boolean enabled = true;
    }
}
