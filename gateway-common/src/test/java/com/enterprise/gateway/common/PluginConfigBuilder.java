package com.enterprise.gateway.common;

import com.enterprise.gateway.common.model.PluginConfig;

public class PluginConfigBuilder {

    private String pluginName = "test-plugin";
    private String pluginType = "CUSTOM";
    private String routeId = null;
    private String config = "{}";
    private Boolean enabled = true;
    private Integer orderNum = 0;

    private PluginConfigBuilder() {
    }

    public static PluginConfigBuilder builder() {
        return new PluginConfigBuilder();
    }

    public PluginConfigBuilder withPluginName(String pluginName) {
        this.pluginName = pluginName;
        return this;
    }

    public PluginConfigBuilder withPluginType(String pluginType) {
        this.pluginType = pluginType;
        return this;
    }

    public PluginConfigBuilder withRouteId(String routeId) {
        this.routeId = routeId;
        return this;
    }

    public PluginConfigBuilder withConfig(String config) {
        this.config = config;
        return this;
    }

    public PluginConfigBuilder withEnabled(Boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    public PluginConfigBuilder withOrderNum(Integer orderNum) {
        this.orderNum = orderNum;
        return this;
    }

    public PluginConfig build() {
        return PluginConfig.builder()
                .pluginName(pluginName)
                .pluginType(pluginType)
                .routeId(routeId)
                .config(config)
                .enabled(enabled)
                .orderNum(orderNum)
                .build();
    }
}
