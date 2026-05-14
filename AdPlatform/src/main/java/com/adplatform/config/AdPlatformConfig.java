package com.adplatform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "adplatform")
public class AdPlatformConfig {
    
    private LockTimeoutConfig lock = new LockTimeoutConfig();
    private PlacementConfig placement = new PlacementConfig();
    private EffectQueueConfig effect = new EffectQueueConfig();
    private TargetingConfig targeting = new TargetingConfig();

    @Data
    public static class LockTimeoutConfig {
        private long defaultWaitTime = 5;
        private long defaultLeaseTime = 30;
        private Map<String, TimeoutLevel> levels = new HashMap<>();
        
        public LockTimeoutConfig() {
            levels.put("critical", new TimeoutLevel(1, 5));
            levels.put("high", new TimeoutLevel(3, 15));
            levels.put("normal", new TimeoutLevel(5, 30));
            levels.put("low", new TimeoutLevel(10, 60));
        }
    }

    @Data
    public static class TimeoutLevel {
        private long waitTime;
        private long leaseTime;
        
        public TimeoutLevel() {}
        
        public TimeoutLevel(long waitTime, long leaseTime) {
            this.waitTime = waitTime;
            this.leaseTime = leaseTime;
        }
    }

    @Data
    public static class PlacementConfig {
        private String queueKey = "placement:tasks";
        private int workerThreads = 2;
        private int maxRetries = 3;
        private long retryInterval = 5000;
    }

    @Data
    public static class EffectQueueConfig {
        private String queueKey = "effect:events";
        private int workerThreads = 3;
        private int batchSize = 100;
    }

    @Data
    public static class TargetingConfig {
        private Map<String, TargetTypeConfig> types = new HashMap<>();
        
        public TargetingConfig() {
            TargetTypeConfig demographic = new TargetTypeConfig();
            demographic.setRequiredFields(java.util.Arrays.asList("age", "gender", "location"));
            demographic.setOptionalFields(java.util.Arrays.asList("income", "education", "occupation"));
            demographic.setDescription("人口统计定向");
            types.put("demographic", demographic);

            TargetTypeConfig geographic = new TargetTypeConfig();
            geographic.setRequiredFields(java.util.Arrays.asList("location"));
            geographic.setOptionalFields(java.util.Arrays.asList("cities", "provinces", "radius"));
            geographic.setDescription("地理定向");
            types.put("geographic", geographic);

            TargetTypeConfig behavior = new TargetTypeConfig();
            behavior.setRequiredFields(java.util.Arrays.asList("behaviors"));
            behavior.setOptionalFields(java.util.Arrays.asList("purchaseFrequency", "lastVisitDays"));
            behavior.setDescription("行为定向");
            types.put("behavior", behavior);

            TargetTypeConfig interest = new TargetTypeConfig();
            interest.setRequiredFields(java.util.Arrays.asList("interests"));
            interest.setOptionalFields(java.util.Arrays.asList("interestLevel", "recentInterests"));
            interest.setDescription("兴趣定向");
            types.put("interest", interest);
        }
    }

    @Data
    public static class TargetTypeConfig {
        private java.util.List<String> requiredFields = new java.util.ArrayList<>();
        private java.util.List<String> optionalFields = new java.util.ArrayList<>();
        private String description;
    }
}
