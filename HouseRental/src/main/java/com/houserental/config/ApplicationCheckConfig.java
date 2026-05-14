package com.houserental.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "application.check")
public class ApplicationCheckConfig {

    private List<CheckRule> rules = new ArrayList<>();
    private String defaultScope = "combined";

    @Data
    public static class CheckRule {
        private String name;
        private String scope;
        private boolean enabled = true;
        private String description;
        private int priority;
    }

    public List<CheckRule> getEnabledRules() {
        return rules.stream()
                .filter(CheckRule::isEnabled)
                .sorted((a, b) -> Integer.compare(a.getPriority(), b.getPriority()))
                .toList();
    }

    public List<CheckRule> getRulesByScope(String scope) {
        return rules.stream()
                .filter(r -> scope.equals(r.getScope()) && r.isEnabled())
                .sorted((a, b) -> Integer.compare(a.getPriority(), b.getPriority()))
                .toList();
    }

    public CheckRule getRuleByName(String name) {
        return rules.stream()
                .filter(r -> name.equals(r.getName()))
                .findFirst()
                .orElse(null);
    }

    public boolean isRuleEnabled(String name) {
        CheckRule rule = getRuleByName(name);
        return rule != null && rule.isEnabled();
    }
}
