package com.enterprise.gateway.admin.plugin;

import com.enterprise.gateway.admin.mapper.PluginConfigMapper;
import com.enterprise.gateway.common.model.PluginConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class PluginManager {

    private final Map<String, PluginConfig> activePlugins = new ConcurrentHashMap<>();
    private final PluginConfigMapper pluginConfigMapper;

    public void loadPlugin(PluginConfig config) {
        String key = buildKey(config.getPluginName(), config.getRouteId());
        activePlugins.put(key, config);
        log.info("Plugin loaded: {} for route: {}", config.getPluginName(), config.getRouteId());
    }

    public void unloadPlugin(String pluginName, String routeId) {
        String key = buildKey(pluginName, routeId);
        activePlugins.remove(key);
        log.info("Plugin unloaded: {} for route: {}", pluginName, routeId);
    }

    public void enablePlugin(Long id) {
        PluginConfig config = pluginConfigMapper.selectById(id);
        if (config != null) {
            config.setEnabled(true);
            pluginConfigMapper.updateById(config);
            loadPlugin(config);
        }
    }

    public void disablePlugin(Long id) {
        PluginConfig config = pluginConfigMapper.selectById(id);
        if (config != null) {
            config.setEnabled(false);
            pluginConfigMapper.updateById(config);
            unloadPlugin(config.getPluginName(), config.getRouteId());
        }
    }

    public boolean isPluginActive(String pluginName, String routeId) {
        String key = buildKey(pluginName, routeId);
        return activePlugins.containsKey(key);
    }

    private String buildKey(String pluginName, String routeId) {
        return pluginName + ":" + (routeId != null ? routeId : "global");
    }
}
