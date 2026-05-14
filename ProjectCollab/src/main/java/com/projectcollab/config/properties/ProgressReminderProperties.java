package com.projectcollab.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "progress.reminder")
public class ProgressReminderProperties {

    private StageThresholds design = new StageThresholds(70, 50, true);
    private StageThresholds development = new StageThresholds(60, 40, true);
    private StageThresholds testing = new StageThresholds(75, 50, true);
    private StageThresholds deployment = new StageThresholds(50, 30, false);
    
    private Map<String, StageThresholds> customStages = new HashMap<>();

    public StageThresholds getDesign() {
        return design;
    }

    public void setDesign(StageThresholds design) {
        this.design = design;
    }

    public StageThresholds getDevelopment() {
        return development;
    }

    public void setDevelopment(StageThresholds development) {
        this.development = development;
    }

    public StageThresholds getTesting() {
        return testing;
    }

    public void setTesting(StageThresholds testing) {
        this.testing = testing;
    }

    public StageThresholds getDeployment() {
        return deployment;
    }

    public void setDeployment(StageThresholds deployment) {
        this.deployment = deployment;
    }

    public Map<String, StageThresholds> getCustomStages() {
        return customStages;
    }

    public void setCustomStages(Map<String, StageThresholds> customStages) {
        this.customStages = customStages;
    }

    public StageThresholds getStageThresholds(String stageCode) {
        if (stageCode == null) {
            return StageThresholds.DEFAULT;
        }
        
        if (customStages.containsKey(stageCode)) {
            return customStages.get(stageCode);
        }
        
        return switch (stageCode.toLowerCase()) {
            case "design" -> design;
            case "development", "dev" -> development;
            case "testing", "test" -> testing;
            case "deployment", "deploy" -> deployment;
            default -> StageThresholds.DEFAULT;
        };
    }

    public int getWarningThreshold(String stageCode) {
        return getStageThresholds(stageCode).getWarningThreshold();
    }

    public int getCriticalThreshold(String stageCode) {
        return getStageThresholds(stageCode).getCriticalThreshold();
    }

    public boolean isReminderEnabled(String stageCode) {
        return getStageThresholds(stageCode).isEnabled();
    }

    public void updateStageThresholds(String stageCode, int warningThreshold, int criticalThreshold, boolean enabled) {
        if (customStages.containsKey(stageCode)) {
            StageThresholds thresholds = customStages.get(stageCode);
            thresholds.setWarningThreshold(warningThreshold);
            thresholds.setCriticalThreshold(criticalThreshold);
            thresholds.setEnabled(enabled);
        } else {
            customStages.put(stageCode, new StageThresholds(warningThreshold, criticalThreshold, enabled));
        }
    }

    public static class StageThresholds {
        
        public static final StageThresholds DEFAULT = new StageThresholds(60, 40, false);
        
        private int warningThreshold;
        private int criticalThreshold;
        private boolean enabled;

        public StageThresholds() {
        }

        public StageThresholds(int warningThreshold, int criticalThreshold, boolean enabled) {
            this.warningThreshold = warningThreshold;
            this.criticalThreshold = criticalThreshold;
            this.enabled = enabled;
        }

        public int getWarningThreshold() {
            return warningThreshold;
        }

        public void setWarningThreshold(int warningThreshold) {
            this.warningThreshold = warningThreshold;
        }

        public int getCriticalThreshold() {
            return criticalThreshold;
        }

        public void setCriticalThreshold(int criticalThreshold) {
            this.criticalThreshold = criticalThreshold;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
