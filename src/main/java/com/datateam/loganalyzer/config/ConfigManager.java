package com.datateam.loganalyzer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ConfigManager {

    private static final Logger logger = LoggerFactory.getLogger(ConfigManager.class);

    private static ConfigManager instance;

    private final ObjectMapper yamlMapper;
    private final ObjectMapper jsonMapper;

    private AppConfig defaultConfig;
    private AppConfig effectiveConfig;
    private String externalConfigDir;

    private final Map<String, Object> configCache = new ConcurrentHashMap<>();

    private ConfigManager() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.yamlMapper.registerModule(new JavaTimeModule());
        this.jsonMapper = new ObjectMapper();
        this.jsonMapper.registerModule(new JavaTimeModule());
    }

    public static synchronized ConfigManager getInstance() {
        if (instance == null) {
            instance = new ConfigManager();
            instance.loadDefaultConfig();
        }
        return instance;
    }

    private void loadDefaultConfig() {
        try {
            InputStream is = getClass().getClassLoader().getResourceAsStream("application.yaml");
            if (is != null) {
                defaultConfig = yamlMapper.readValue(is, AppConfig.class);
                logger.info("Loaded default configuration from classpath:application.yaml");
            } else {
                logger.warn("No default configuration found in classpath, using defaults");
                defaultConfig = new AppConfig();
                defaultConfig.setAppName("log-analyzer");
                defaultConfig.setVersion("1.0.0");
                defaultConfig.setLogLevel("INFO");
                defaultConfig.setTimeZone("UTC");
            }
            effectiveConfig = defaultConfig;
        } catch (IOException e) {
            logger.error("Failed to load default configuration", e);
            defaultConfig = new AppConfig();
            defaultConfig.setAppName("log-analyzer");
            defaultConfig.setVersion("1.0.0");
            defaultConfig.setLogLevel("INFO");
            defaultConfig.setTimeZone("UTC");
            effectiveConfig = defaultConfig;
        }
    }

    public void loadExternalConfig(String configDir) throws IOException {
        this.externalConfigDir = configDir;
        Path configPath = Paths.get(configDir);

        if (!Files.exists(configPath)) {
            logger.warn("External config directory does not exist: {}", configDir);
            return;
        }

        AppConfig mergedConfig = copyConfig(defaultConfig);

        Path appConfigPath = configPath.resolve("application.yaml");
        if (Files.exists(appConfigPath)) {
            try {
                AppConfig externalConfig = yamlMapper.readValue(appConfigPath.toFile(), AppConfig.class);
                mergedConfig = mergeConfigs(mergedConfig, externalConfig);
                logger.info("Loaded external configuration from: {}", appConfigPath);
            } catch (IOException e) {
                logger.error("Failed to load external application.yaml", e);
                throw e;
            }
        }

        Path grokPatternsPath = configPath.resolve("grok-patterns.yaml");
        if (Files.exists(grokPatternsPath)) {
            try {
                Map<String, Object> patterns = yamlMapper.readValue(grokPatternsPath.toFile(), Map.class);
                mergedConfig.getGrokPatterns().putAll(patterns);
                logger.info("Loaded external grok patterns from: {}", grokPatternsPath);
            } catch (IOException e) {
                logger.error("Failed to load external grok-patterns.yaml", e);
                throw e;
            }
        }

        Path alertRulesPath = configPath.resolve("alert-rules.yaml");
        if (Files.exists(alertRulesPath)) {
            try {
                List<Map<String, Object>> rulesList = yamlMapper.readValue(alertRulesPath.toFile(), List.class);
                List<com.datateam.loganalyzer.model.AlertRule> rules = new ArrayList<>();
                for (Map<String, Object> ruleMap : rulesList) {
                    com.datateam.loganalyzer.model.AlertRule rule = jsonMapper.convertValue(ruleMap, com.datateam.loganalyzer.model.AlertRule.class);
                    rules.add(rule);
                }
                mergedConfig.getAlertRules().addAll(rules);
                logger.info("Loaded external alert rules from: {}", alertRulesPath);
            } catch (IOException e) {
                logger.error("Failed to load external alert-rules.yaml", e);
                throw e;
            }
        }

        Path notificationPath = configPath.resolve("notification.yaml");
        if (Files.exists(notificationPath)) {
            try {
                com.datateam.loganalyzer.model.NotificationConfig notificationConfig = yamlMapper.readValue(notificationPath.toFile(), com.datateam.loganalyzer.model.NotificationConfig.class);
                mergedConfig.setNotification(notificationConfig);
                logger.info("Loaded external notification config from: {}", notificationPath);
            } catch (IOException e) {
                logger.error("Failed to load external notification.yaml", e);
                throw e;
            }
        }

        Path templatesPath = configPath.resolve("templates.yaml");
        if (Files.exists(templatesPath)) {
            try {
                Map<String, Object> templates = yamlMapper.readValue(templatesPath.toFile(), Map.class);
                mergedConfig.getTemplates().putAll(templates);
                logger.info("Loaded external templates from: {}", templatesPath);
            } catch (IOException e) {
                logger.error("Failed to load external templates.yaml", e);
                throw e;
            }
        }

        this.effectiveConfig = mergedConfig;
        logger.info("External configuration loaded successfully from: {}", configDir);
    }

    private AppConfig copyConfig(AppConfig source) {
        try {
            String json = jsonMapper.writeValueAsString(source);
            return jsonMapper.readValue(json, AppConfig.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to copy config", e);
        }
    }

    @SuppressWarnings("unchecked")
    private AppConfig mergeConfigs(AppConfig base, AppConfig override) {
        if (override == null) {
            return base;
        }

        try {
            Map<String, Object> baseMap = jsonMapper.convertValue(base, Map.class);
            Map<String, Object> overrideMap = jsonMapper.convertValue(override, Map.class);
            mergeMaps(baseMap, overrideMap);
            return jsonMapper.convertValue(baseMap, AppConfig.class);
        } catch (Exception e) {
            logger.error("Failed to merge configurations", e);
            return base;
        }
    }

    @SuppressWarnings("unchecked")
    private void mergeMaps(Map<String, Object> base, Map<String, Object> override) {
        for (Map.Entry<String, Object> entry : override.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof Map && base.get(key) instanceof Map) {
                Map<String, Object> baseChild = (Map<String, Object>) base.get(key);
                Map<String, Object> overrideChild = (Map<String, Object>) value;
                mergeMaps(baseChild, overrideChild);
            } else if (value instanceof List && base.get(key) instanceof List) {
                List<Object> baseList = (List<Object>) base.get(key);
                List<Object> overrideList = (List<Object>) value;
                baseList.addAll(overrideList);
            } else {
                base.put(key, value);
            }
        }
    }

    public AppConfig getConfig() {
        return effectiveConfig;
    }

    public AppConfig getDefaultConfig() {
        return defaultConfig;
    }

    public String getExternalConfigDir() {
        return externalConfigDir;
    }

    public boolean hasExternalConfig() {
        return externalConfigDir != null;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, T defaultValue) {
        String[] parts = key.split("\\.");
        Map<String, Object> current = jsonMapper.convertValue(effectiveConfig, Map.class);

        for (int i = 0; i < parts.length - 1; i++) {
            Object value = current.get(parts[i]);
            if (value instanceof Map) {
                current = (Map<String, Object>) value;
            } else {
                return defaultValue;
            }
        }

        Object value = current.get(parts[parts.length - 1]);
        if (value == null) {
            return defaultValue;
        }

        return (T) value;
    }

    public void reload() throws IOException {
        loadDefaultConfig();
        if (externalConfigDir != null) {
            loadExternalConfig(externalConfigDir);
        }
        configCache.clear();
        logger.info("Configuration reloaded");
    }
}
