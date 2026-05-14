package com.configcenter.validation.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "config-center.validation")
public class ValidationProperties {
    
    private Boolean enabled = true;
    
    private List<ValidationRule> rules = new ArrayList<>();
    
    private List<ConfigRuleMapping> configRuleMappings = new ArrayList<>();
    
    private Map<String, List<String>> configRules = new HashMap<>();
    
    @Data
    public static class ValidationRule {
        private String ruleId;
        private String ruleType;
        private String name;
        private String description;
        private java.util.Map<String, Object> params = new java.util.HashMap<>();
        private Boolean enabled = true;
        private Integer priority = 100;
        private String errorMessage;
    }
    
    @Data
    public static class ConfigRuleMapping {
        private String configIdPattern;
        private String configKeyPattern;
        private List<String> ruleIds = new ArrayList<>();
        private List<ValidationRule> inlineRules = new ArrayList<>();
        private Boolean enabled = true;
        private String description;
    }
}
