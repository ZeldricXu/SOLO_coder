package com.enterprise.gateway.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.gateway.admin.mapper.PluginConfigMapper;
import com.enterprise.gateway.admin.plugin.PluginManager;
import com.enterprise.gateway.common.model.PluginConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PluginService {

    private final PluginConfigMapper pluginConfigMapper;
    private final PluginManager pluginManager;

    public List<PluginConfig> listPlugins(String pluginType, String routeId) {
        LambdaQueryWrapper<PluginConfig> query = new LambdaQueryWrapper<>();
        if (pluginType != null && !pluginType.isEmpty()) {
            query.eq(PluginConfig::getPluginType, pluginType);
        }
        if (routeId != null && !routeId.isEmpty()) {
            query.eq(PluginConfig::getRouteId, routeId);
        }
        query.orderByDesc(PluginConfig::getCreatedAt);
        return pluginConfigMapper.selectList(query);
    }

    public PluginConfig getPluginById(Long id) {
        return pluginConfigMapper.selectById(id);
    }

    public PluginConfig createPlugin(PluginConfig config) {
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        if (config.getEnabled() == null) {
            config.setEnabled(true);
        }
        pluginConfigMapper.insert(config);
        if (config.getEnabled()) {
            pluginManager.loadPlugin(config);
        }
        return config;
    }

    public PluginConfig updatePlugin(PluginConfig config) {
        config.setUpdatedAt(LocalDateTime.now());
        PluginConfig existing = pluginConfigMapper.selectById(config.getId());
        if (existing != null) {
            pluginManager.unloadPlugin(existing.getPluginName(), existing.getRouteId());
        }
        pluginConfigMapper.updateById(config);
        PluginConfig updated = pluginConfigMapper.selectById(config.getId());
        if (updated.getEnabled()) {
            pluginManager.loadPlugin(updated);
        }
        return updated;
    }

    public void deletePlugin(Long id) {
        PluginConfig config = pluginConfigMapper.selectById(id);
        if (config != null) {
            pluginManager.unloadPlugin(config.getPluginName(), config.getRouteId());
            pluginConfigMapper.deleteById(id);
        }
    }

    public PluginConfig togglePlugin(Long id) {
        PluginConfig config = pluginConfigMapper.selectById(id);
        if (config != null) {
            config.setEnabled(!config.getEnabled());
            config.setUpdatedAt(LocalDateTime.now());
            pluginConfigMapper.updateById(config);
            if (config.getEnabled()) {
                pluginManager.loadPlugin(config);
            } else {
                pluginManager.unloadPlugin(config.getPluginName(), config.getRouteId());
            }
        }
        return config;
    }

    @PostConstruct
    public void loadAllActive() {
        List<PluginConfig> configs = pluginConfigMapper.selectList(new LambdaQueryWrapper<PluginConfig>()
                .eq(PluginConfig::getEnabled, true));
        for (PluginConfig config : configs) {
            pluginManager.loadPlugin(config);
        }
        log.info("Loaded {} active plugins", configs.size());
    }

    public void refreshAll() {
        loadAllActive();
    }
}
