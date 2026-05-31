package com.solocoder.platform.notification.config;

import com.solocoder.platform.common.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class NotificationConfigManager {

    private final AtomicReference<NotificationDynamicConfig> currentConfig = new AtomicReference<>();
    private final Path configFilePath;
    private final Map<String, ConfigChangeListener> listeners = new ConcurrentHashMap<>();

    public NotificationConfigManager(@Value("${notification.config.file:./config/notification-config.json}") String configFilePath) {
        this.configFilePath = Paths.get(configFilePath);
        NotificationDynamicConfig initial = loadFromFile();
        currentConfig.set(initial != null ? initial : createDefaultConfig());
        log.info("Notification config manager initialized: file={}", this.configFilePath.toAbsolutePath());
    }

    public NotificationDynamicConfig getCurrentConfig() {
        return currentConfig.get();
    }

    public ChannelConfig getChannelConfig(String channelType) {
        return currentConfig.get().getChannelConfig(channelType);
    }

    public boolean isChannelEnabled(String channelType) {
        return currentConfig.get().isChannelEnabled(channelType);
    }

    public NotificationDynamicConfig reload() {
        log.info("Reloading notification configuration...");
        NotificationDynamicConfig newConfig = loadFromFile();
        if (newConfig != null) {
            NotificationDynamicConfig oldConfig = currentConfig.get();
            newConfig.setConfigVersion(oldConfig.getConfigVersion() + 1);
            newConfig.setLoadedAt(System.currentTimeMillis());
            currentConfig.set(newConfig);
            notifyListeners(oldConfig, newConfig);
            log.info("Notification configuration reloaded: version={}", newConfig.getConfigVersion());
            return newConfig;
        }
        log.warn("Failed to reload notification configuration, keeping current config");
        return currentConfig.get();
    }

    public void updateChannelConfig(String channelType, ChannelConfig config) {
        NotificationDynamicConfig current = currentConfig.get();
        Map<String, ChannelConfig> newChannelConfigs = new ConcurrentHashMap<>(current.getChannelConfigs());
        newChannelConfigs.put(channelType, config);

        NotificationDynamicConfig newConfig = NotificationDynamicConfig.builder()
                .channelConfigs(newChannelConfigs)
                .globalSettings(current.getGlobalSettings())
                .configVersion(current.getConfigVersion() + 1)
                .loadedAt(System.currentTimeMillis())
                .build();

        NotificationDynamicConfig oldConfig = currentConfig.getAndSet(newConfig);
        saveToFile(newConfig);
        notifyListeners(oldConfig, newConfig);
        log.info("Channel config updated: channel={}, enabled={}, version={}", channelType, config.isEnabled(), newConfig.getConfigVersion());
    }

    public void registerListener(String name, ConfigChangeListener listener) {
        listeners.put(name, listener);
        log.info("Config change listener registered: {}", name);
    }

    public void unregisterListener(String name) {
        listeners.remove(name);
    }

    public long getConfigVersion() {
        return currentConfig.get().getConfigVersion();
    }

    private void notifyListeners(NotificationDynamicConfig oldConfig, NotificationDynamicConfig newConfig) {
        listeners.forEach((name, listener) -> {
            try {
                listener.onConfigChanged(oldConfig, newConfig);
            } catch (Exception e) {
                log.error("Config change listener error: {}", name, e);
            }
        });
    }

    private NotificationDynamicConfig loadFromFile() {
        try {
            if (Files.exists(configFilePath)) {
                String json = Files.readString(configFilePath);
                return JsonUtils.fromJson(json, NotificationDynamicConfig.class);
            }
        } catch (Exception e) {
            log.error("Failed to load notification config from file: {}", configFilePath, e);
        }
        return null;
    }

    private void saveToFile(NotificationDynamicConfig config) {
        try {
            Files.createDirectories(configFilePath.getParent());
            String json = JsonUtils.toJson(config);
            Files.writeString(configFilePath, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.debug("Notification config saved to file: {}", configFilePath);
        } catch (IOException e) {
            log.error("Failed to save notification config to file: {}", configFilePath, e);
        }
    }

    private NotificationDynamicConfig createDefaultConfig() {
        Map<String, ChannelConfig> defaults = new HashMap<>();
        defaults.put("EMAIL", ChannelConfig.defaultConfig("EMAIL"));
        defaults.put("SMS", ChannelConfig.defaultConfig("SMS"));
        defaults.put("WEBHOOK", ChannelConfig.defaultConfig("WEBHOOK"));

        return NotificationDynamicConfig.builder()
                .channelConfigs(defaults)
                .globalSettings(Map.of("defaultRetryEnabled", "true", "maxConcurrentSends", "50"))
                .configVersion(1)
                .loadedAt(System.currentTimeMillis())
                .build();
    }

    @FunctionalInterface
    public interface ConfigChangeListener {
        void onConfigChanged(NotificationDynamicConfig oldConfig, NotificationDynamicConfig newConfig);
    }
}
