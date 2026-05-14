package com.projectcollab.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "project.types")
public class ProjectTypeProperties {

    private Map<String, ProjectTypeConfig> configs = new HashMap<>();

    public ProjectTypeProperties() {
        configs.put("development", new ProjectTypeConfig(
                "软件开发", "Software Development",
                List.of("design", "development", "testing", "deployment"),
                true, List.of("high", "medium", "low")
        ));
        configs.put("marketing", new ProjectTypeConfig(
                "市场营销", "Marketing Campaign",
                List.of("planning", "execution", "review", "launch"),
                false, List.of("high", "normal", "low")
        ));
        configs.put("research", new ProjectTypeConfig(
                "研究项目", "Research Project",
                List.of("literature", "experiment", "analysis", "report"),
                true, List.of("critical", "high", "normal")
        ));
    }

    public Map<String, ProjectTypeConfig> getConfigs() {
        return configs;
    }

    public void setConfigs(Map<String, ProjectTypeConfig> configs) {
        this.configs = configs;
    }

    public ProjectTypeConfig getTypeConfig(String type) {
        if (type == null) {
            return getDefaultType();
        }
        return configs.getOrDefault(type, getDefaultType());
    }

    public ProjectTypeConfig getDefaultType() {
        return configs.get("development");
    }

    public boolean isValidType(String type) {
        return configs.containsKey(type);
    }

    public List<String> getAllTypes() {
        return new ArrayList<>(configs.keySet());
    }

    public List<String> getStagesForType(String type) {
        return getTypeConfig(type).getStages();
    }

    public void addTypeConfig(String typeCode, ProjectTypeConfig config) {
        configs.put(typeCode, config);
    }

    public void removeTypeConfig(String typeCode) {
        if (!"development".equals(typeCode)) {
            configs.remove(typeCode);
        }
    }

    public static class ProjectTypeConfig {
        private String displayName;
        private String description;
        private List<String> stages = new ArrayList<>();
        private boolean requireSprintPlanning = false;
        private List<String> allowedPriorities = new ArrayList<>();

        public ProjectTypeConfig() {
        }

        public ProjectTypeConfig(String displayName, String description, 
                List<String> stages, boolean requireSprintPlanning, 
                List<String> allowedPriorities) {
            this.displayName = displayName;
            this.description = description;
            this.stages = stages;
            this.requireSprintPlanning = requireSprintPlanning;
            this.allowedPriorities = allowedPriorities;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public List<String> getStages() {
            return stages;
        }

        public void setStages(List<String> stages) {
            this.stages = stages;
        }

        public boolean isRequireSprintPlanning() {
            return requireSprintPlanning;
        }

        public void setRequireSprintPlanning(boolean requireSprintPlanning) {
            this.requireSprintPlanning = requireSprintPlanning;
        }

        public List<String> getAllowedPriorities() {
            return allowedPriorities;
        }

        public void setAllowedPriorities(List<String> allowedPriorities) {
            this.allowedPriorities = allowedPriorities;
        }
    }
}
