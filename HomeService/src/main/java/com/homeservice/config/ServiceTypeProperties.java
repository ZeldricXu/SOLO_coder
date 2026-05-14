package com.homeservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.util.ArrayList;
import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "homeservice.service-types")
public class ServiceTypeProperties {

    private List<ServiceTypeConfig> defaults = new ArrayList<>();
    private boolean autoCreateFromConfig = true;

    public List<ServiceTypeConfig> getDefaults() {
        return defaults;
    }

    public void setDefaults(List<ServiceTypeConfig> defaults) {
        this.defaults = defaults;
    }

    public boolean isAutoCreateFromConfig() {
        return autoCreateFromConfig;
    }

    public void setAutoCreateFromConfig(boolean autoCreateFromConfig) {
        this.autoCreateFromConfig = autoCreateFromConfig;
    }

    public static class ServiceTypeConfig {
        private String code;
        private String name;
        private String description;
        private Double basePrice;
        private Boolean active = true;
        private List<String> supportedRegions = new ArrayList<>();
        private Integer minDuration = 1;
        private Integer maxDuration = 8;

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

        public Double getBasePrice() {
            return basePrice;
        }

        public void setBasePrice(Double basePrice) {
            this.basePrice = basePrice;
        }

        public Boolean getActive() {
            return active;
        }

        public void setActive(Boolean active) {
            this.active = active;
        }

        public List<String> getSupportedRegions() {
            return supportedRegions;
        }

        public void setSupportedRegions(List<String> supportedRegions) {
            this.supportedRegions = supportedRegions;
        }

        public Integer getMinDuration() {
            return minDuration;
        }

        public void setMinDuration(Integer minDuration) {
            this.minDuration = minDuration;
        }

        public Integer getMaxDuration() {
            return maxDuration;
        }

        public void setMaxDuration(Integer maxDuration) {
            this.maxDuration = maxDuration;
        }
    }
}
