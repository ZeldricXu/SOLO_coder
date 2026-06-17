package com.loganalytics.common.config;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

public class AppConfig {
    private final Properties properties;

    public AppConfig() {
        this.properties = new Properties();
        loadFromEnv();
    }

    public AppConfig(String configFile) throws IOException {
        this.properties = new Properties();
        try (FileInputStream fis = new FileInputStream(configFile)) {
            properties.load(fis);
        }
        loadFromEnv();
    }

    private void loadFromEnv() {
        for (String envName : System.getenv().keySet()) {
            if (envName.startsWith("LOGANALYTICS_")) {
                String propName = envName.substring("LOGANALYTICS_".length())
                        .toLowerCase()
                        .replace('_', '.');
                properties.setProperty(propName, System.getenv(envName));
            }
        }
    }

    public String getString(String key) {
        return properties.getProperty(key);
    }

    public String getString(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        String value = properties.getProperty(key);
        return value != null ? Integer.parseInt(value) : defaultValue;
    }

    public long getLong(String key, long defaultValue) {
        String value = properties.getProperty(key);
        return value != null ? Long.parseLong(value) : defaultValue;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String value = properties.getProperty(key);
        return value != null ? Boolean.parseBoolean(value) : defaultValue;
    }

    public double getDouble(String key, double defaultValue) {
        String value = properties.getProperty(key);
        return value != null ? Double.parseDouble(value) : defaultValue;
    }

    public static AppConfig loadFromFile(String path) throws IOException {
        return new AppConfig(path);
    }

    public static AppConfig loadDefault() {
        return new AppConfig();
    }

    public Properties asProperties() {
        return new Properties(properties);
    }
}
