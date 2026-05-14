package com.maplocation.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplocation.model.RouteTypeConfig;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
public class RouteTypeConfigService {

    private final Map<String, RouteTypeConfig> configs = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${map.route-types.config:}")
    private String customConfigJson;

    @PostConstruct
    public void initialize() {
        loadDefaultConfigs();

        if (customConfigJson != null && !customConfigJson.isEmpty()) {
            try {
                List<RouteTypeConfig> customConfigs = objectMapper.readValue(
                        customConfigJson,
                        new TypeReference<List<RouteTypeConfig>>() {}
                );
                mergeConfigs(customConfigs);
            } catch (Exception e) {
                log.warn("Failed to load custom route type configs: {}", e.getMessage());
            }
        }

        log.info("Loaded {} route type configurations: {}",
                configs.size(),
                configs.keySet());
    }

    private void loadDefaultConfigs() {
        List<RouteTypeConfig> defaultConfigs = Arrays.asList(
                RouteTypeConfig.builder()
                        .typeCode("driving")
                        .typeName("驾车")
                        .typeDescription("汽车行驶路径")
                        .distanceFactor(1.3)
                        .averageSpeedKmh(50.0)
                        .enabled(true)
                        .priority(1)
                        .build(),

                RouteTypeConfig.builder()
                        .typeCode("walking")
                        .typeName("步行")
                        .typeDescription("步行路径")
                        .distanceFactor(1.0)
                        .averageSpeedKmh(5.0)
                        .enabled(true)
                        .priority(2)
                        .build(),

                RouteTypeConfig.builder()
                        .typeCode("transit")
                        .typeName("公交")
                        .typeDescription("公共交通路径")
                        .distanceFactor(1.2)
                        .averageSpeedKmh(30.0)
                        .enabled(true)
                        .priority(3)
                        .build(),

                RouteTypeConfig.builder()
                        .typeCode("cycling")
                        .typeName("骑行")
                        .typeDescription("自行车骑行路径")
                        .distanceFactor(1.05)
                        .averageSpeedKmh(15.0)
                        .enabled(true)
                        .priority(4)
                        .build(),

                RouteTypeConfig.builder()
                        .typeCode("metro")
                        .typeName("地铁")
                        .typeDescription("地铁轨道交通路径")
                        .distanceFactor(1.1)
                        .averageSpeedKmh(40.0)
                        .enabled(true)
                        .priority(5)
                        .build()
        );

        for (RouteTypeConfig config : defaultConfigs) {
            configs.put(config.getTypeCode(), config);
        }
    }

    private void mergeConfigs(List<RouteTypeConfig> customConfigs) {
        for (RouteTypeConfig config : customConfigs) {
            String typeCode = config.getTypeCode();

            if (configs.containsKey(typeCode)) {
                RouteTypeConfig existing = configs.get(typeCode);
                if (config.getDistanceFactor() > 0) {
                    existing.setDistanceFactor(config.getDistanceFactor());
                }
                if (config.getAverageSpeedKmh() > 0) {
                    existing.setAverageSpeedKmh(config.getAverageSpeedKmh());
                }
                if (config.getTypeName() != null) {
                    existing.setTypeName(config.getTypeName());
                }
                if (config.getTypeDescription() != null) {
                    existing.setTypeDescription(config.getTypeDescription());
                }
                existing.setEnabled(config.isEnabled());
                if (config.getPriority() > 0) {
                    existing.setPriority(config.getPriority());
                }
                log.info("Updated route type config: {}", typeCode);
            } else {
                if (config.isEnabled() && config.getAverageSpeedKmh() > 0) {
                    configs.put(typeCode, config);
                    log.info("Added new route type config: {}", typeCode);
                }
            }
        }
    }

    public RouteTypeConfig getConfig(String routeType) {
        return configs.get(routeType);
    }

    public boolean isRouteTypeSupported(String routeType) {
        RouteTypeConfig config = configs.get(routeType);
        return config != null && config.isEnabled();
    }

    public double getDistanceFactor(String routeType) {
        RouteTypeConfig config = configs.get(routeType);
        if (config != null) {
            return config.getDistanceFactor();
        }
        log.warn("Unknown route type: {}, using default factor 1.0", routeType);
        return 1.0;
    }

    public double getAverageSpeedKmh(String routeType) {
        RouteTypeConfig config = configs.get(routeType);
        if (config != null) {
            return config.getAverageSpeedKmh();
        }
        log.warn("Unknown route type: {}, using default speed 50.0", routeType);
        return 50.0;
    }

    public List<RouteTypeConfig> getAllConfigs() {
        return configs.values().stream()
                .filter(RouteTypeConfig::isEnabled)
                .sorted(Comparator.comparingInt(RouteTypeConfig::getPriority))
                .collect(Collectors.toList());
    }

    public List<String> getAllSupportedTypes() {
        return getAllConfigs().stream()
                .map(RouteTypeConfig::getTypeCode)
                .collect(Collectors.toList());
    }

    public String getDefaultRouteType() {
        List<RouteTypeConfig> sorted = getAllConfigs();
        return sorted.isEmpty() ? "driving" : sorted.get(0).getTypeCode();
    }

    public void addOrUpdateConfig(RouteTypeConfig config) {
        if (config.getTypeCode() == null || config.getTypeCode().isEmpty()) {
            throw new IllegalArgumentException("Route type code is required");
        }
        if (config.getAverageSpeedKmh() <= 0) {
            throw new IllegalArgumentException("Average speed must be positive");
        }
        if (config.getDistanceFactor() <= 0) {
            throw new IllegalArgumentException("Distance factor must be positive");
        }

        configs.put(config.getTypeCode(), config);
        log.info("Route type config updated: {}", config.getTypeCode());
    }

    public void disableRouteType(String routeType) {
        RouteTypeConfig config = configs.get(routeType);
        if (config != null) {
            config.setEnabled(false);
            log.info("Disabled route type: {}", routeType);
        }
    }

    public void enableRouteType(String routeType) {
        RouteTypeConfig config = configs.get(routeType);
        if (config != null) {
            config.setEnabled(true);
            log.info("Enabled route type: {}", routeType);
        }
    }
}
