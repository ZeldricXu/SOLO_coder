package com.metricplatform.plugin;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Data
@Component
public class PluginManager implements ApplicationContextAware {

    private ApplicationContext applicationContext;
    private final Map<String, MybatisPlugin> plugins = new ConcurrentHashMap<>();
    private final Map<String, Boolean> pluginStatus = new ConcurrentHashMap<>();

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
        loadPlugins();
    }

    private void loadPlugins() {
        Map<String, MybatisPlugin> pluginBeans = applicationContext.getBeansOfType(MybatisPlugin.class);

        List<MybatisPlugin> sortedPlugins = pluginBeans.values().stream()
                .sorted(Comparator.comparingInt(MybatisPlugin::getOrder))
                .collect(Collectors.toList());

        for (MybatisPlugin plugin : sortedPlugins) {
            plugins.put(plugin.getName(), plugin);
            pluginStatus.put(plugin.getName(), plugin.isEnabled());
            log.info("加载MyBatis插件: {} (顺序: {}, 启用: {})",
                    plugin.getName(), plugin.getOrder(), plugin.isEnabled());
        }

        log.info("共加载 {} 个MyBatis插件", plugins.size());
    }

    public void enablePlugin(String pluginName) {
        if (plugins.containsKey(pluginName)) {
            pluginStatus.put(pluginName, true);
            log.info("插件已启用: {}", pluginName);
        } else {
            throw new IllegalArgumentException("插件不存在: " + pluginName);
        }
    }

    public void disablePlugin(String pluginName) {
        if (plugins.containsKey(pluginName)) {
            pluginStatus.put(pluginName, false);
            log.info("插件已禁用: {}", pluginName);
        } else {
            throw new IllegalArgumentException("插件不存在: " + pluginName);
        }
    }

    public boolean isPluginEnabled(String pluginName) {
        return pluginStatus.getOrDefault(pluginName, false);
    }

    public List<PluginInfo> getAllPluginInfo() {
        return plugins.values().stream()
                .sorted(Comparator.comparingInt(MybatisPlugin::getOrder))
                .map(plugin -> new PluginInfo(
                        plugin.getName(),
                        plugin.getDescription(),
                        plugin.getOrder(),
                        isPluginEnabled(plugin.getName()),
                        plugin.getClass().getName()
                ))
                .collect(Collectors.toList());
    }

    public MybatisPlugin getPlugin(String pluginName) {
        return plugins.get(pluginName);
    }

    @Data
    @lombok.AllArgsConstructor
    public static class PluginInfo {
        private String name;
        private String description;
        private int order;
        private boolean enabled;
        private String className;
    }
}
