package com.solo.config.module.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "config.multi-source")
public class ConfigSourceProperties {

    private boolean enabled = true;
    private long refreshInterval = 30000;
    private List<SourceConfig> sources = new ArrayList<>();

    @Data
    public static class SourceConfig {
        private String type;
        private int priority;
        private boolean enabled = true;
        private boolean readOnly = false;
        private boolean writeOnly = false;
    }
}
