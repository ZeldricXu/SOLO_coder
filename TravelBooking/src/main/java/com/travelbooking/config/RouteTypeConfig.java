package com.travelbooking.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.*;

@Data
@Configuration
@ConfigurationProperties(prefix = "route")
@Slf4j
public class RouteTypeConfig {

    private List<RouteTypeDefinition> types = new ArrayList<>();
    private Map<String, RouteTypeDefinition> typeMap = new HashMap<>();

    @PostConstruct
    public void init() {
        for (RouteTypeDefinition type : types) {
            typeMap.put(type.getCode(), type);
            log.info("加载线路类型配置: code={}, name={}, enabled={}", 
                    type.getCode(), type.getName(), type.isEnabled());
        }
        log.info("共加载 {} 个线路类型配置", types.size());
    }

    public List<RouteTypeDefinition> getAllEnabledTypes() {
        return types.stream()
                .filter(RouteTypeDefinition::isEnabled)
                .sorted(Comparator.comparingInt(RouteTypeDefinition::getOrder))
                .toList();
    }

    public List<String> getAllEnabledTypeCodes() {
        return getAllEnabledTypes().stream()
                .map(RouteTypeDefinition::getCode)
                .toList();
    }

    public Optional<RouteTypeDefinition> getTypeByCode(String code) {
        if (code == null) return Optional.empty();
        RouteTypeDefinition type = typeMap.get(code);
        if (type != null && type.isEnabled()) {
            return Optional.of(type);
        }
        return Optional.empty();
    }

    public boolean isTypeEnabled(String code) {
        return getTypeByCode(code).isPresent();
    }

    public RouteTypeDefinition getDefaultType() {
        return types.stream()
                .filter(RouteTypeDefinition::isEnabled)
                .min(Comparator.comparingInt(RouteTypeDefinition::getOrder))
                .orElse(null);
    }

    public int size() {
        return (int) types.stream().filter(RouteTypeDefinition::isEnabled).count();
    }

    @Data
    public static class RouteTypeDefinition {
        private String code;
        private String name;
        private String description;
        private boolean enabled = true;
        private int order = 0;
        private int defaultDuration = 5;
        private double priceFactor = 1.0;
        private String icon;
        private List<String> tags = new ArrayList<>();
    }
}
