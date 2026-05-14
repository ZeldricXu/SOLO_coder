package com.fooddelivery.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "fooddelivery.restaurant")
public class RestaurantTypeConfigProperties {

    private Map<String, RestaurantTypeConfig> types = new HashMap<>();

    private String defaultType = "other";

    @Data
    public static class RestaurantTypeConfig {
        private String code;
        private String name;
        private String description;
        private String icon;
        private List<String> tags = new ArrayList<>();
        private boolean enabled = true;
    }

    public RestaurantTypeConfig getTypeConfig(String typeCode) {
        RestaurantTypeConfig config = types.get(typeCode);
        if (config == null) {
            config = types.get(defaultType);
        }
        return config != null ? config : createDefaultType();
    }

    private RestaurantTypeConfig createDefaultType() {
        RestaurantTypeConfig config = new RestaurantTypeConfig();
        config.setCode("other");
        config.setName("其他");
        config.setDescription("其他类型");
        config.setEnabled(true);
        return config;
    }

    public List<RestaurantTypeConfig> getAllEnabledTypes() {
        List<RestaurantTypeConfig> enabledTypes = new ArrayList<>();
        for (RestaurantTypeConfig config : types.values()) {
            if (config.isEnabled()) {
                enabledTypes.add(config);
            }
        }
        return enabledTypes;
    }

    public boolean isValidType(String typeCode) {
        RestaurantTypeConfig config = types.get(typeCode);
        return config != null && config.isEnabled();
    }

    public String getTypeName(String typeCode) {
        RestaurantTypeConfig config = getTypeConfig(typeCode);
        return config.getName();
    }

    public List<String> getAllTypeCodes() {
        return new ArrayList<>(types.keySet());
    }
}
