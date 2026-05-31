package com.solo.config.module.notification;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "notification")
public class NotificationProperties {

    private List<ProviderConfig> providers = new ArrayList<>();
    private Suppression suppression = new Suppression();

    @Data
    public static class ProviderConfig {
        private String type;
        private boolean enabled = true;
        private int priority = 1;
    }

    @Data
    public static class Suppression {
        private long windowSize = 60000;
        private int maxAlertsPerWindow = 10;
    }
}
