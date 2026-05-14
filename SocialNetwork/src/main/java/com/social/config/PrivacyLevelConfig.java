package com.social.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "social.privacy")
public class PrivacyLevelConfig {

    private Map<String, PrivacyLevelDefinition> friendRequestPolicies = new HashMap<>();
    private Map<String, PrivacyLevelDefinition> messagePolicies = new HashMap<>();
    private Map<String, PrivacyLevelDefinition> postVisibilities = new HashMap<>();
    private Map<String, PrivacyLevelDefinition> profileVisibilities = new HashMap<>();

    public static class PrivacyLevelDefinition {
        private String code;
        private String name;
        private String description;
        private boolean enabled;
        private int priority;

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

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

        public int getPriority() {
            return priority;
        }

        public void setPriority(int priority) {
            this.priority = priority;
        }
    }
}
