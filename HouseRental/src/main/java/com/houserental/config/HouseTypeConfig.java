package com.houserental.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "house")
public class HouseTypeConfig {

    private List<HouseType> types = new ArrayList<>();
    private String defaultType = "apartment";

    @Data
    public static class HouseType {
        private String type;
        private String name;
        private String description;
        private boolean enabled = true;
        private String category;
    }

    public List<HouseType> getEnabledTypes() {
        return types.stream()
                .filter(HouseType::isEnabled)
                .toList();
    }

    public List<String> getEnabledTypeCodes() {
        return types.stream()
                .filter(HouseType::isEnabled)
                .map(HouseType::getType)
                .toList();
    }

    public HouseType getType(String typeCode) {
        return types.stream()
                .filter(t -> typeCode.equals(t.getType()))
                .findFirst()
                .orElse(null);
    }

    public boolean isValidType(String typeCode) {
        return getEnabledTypeCodes().contains(typeCode);
    }

    public String getDefaultType() {
        if (isValidType(defaultType)) {
            return defaultType;
        }
        List<HouseType> enabled = getEnabledTypes();
        return enabled.isEmpty() ? "apartment" : enabled.get(0).getType();
    }

    public List<HouseType> getTypesByCategory(String category) {
        return types.stream()
                .filter(t -> category.equals(t.getCategory()) && t.isEnabled())
                .toList();
    }
}
