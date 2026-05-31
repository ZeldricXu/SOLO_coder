package com.solo.config.module.config.source;

import com.solo.config.module.config.ConfigSource;
import com.solo.config.module.config.ConfigSourceProperties;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public abstract class AbstractConfigSource implements ConfigSource {

    protected final ConfigSourceProperties properties;

    protected ConfigSourceProperties.SourceConfig getSourceConfig() {
        return properties.getSources().stream()
                .filter(s -> s.getType().equals(getType()))
                .findFirst()
                .orElseGet(() -> {
                    ConfigSourceProperties.SourceConfig defaultConfig = new ConfigSourceProperties.SourceConfig();
                    defaultConfig.setType(getType());
                    defaultConfig.setEnabled(true);
                    defaultConfig.setPriority(getPriority());
                    return defaultConfig;
                });
    }

    @Override
    public boolean isEnabled() {
        return getSourceConfig().isEnabled();
    }

    @Override
    public boolean isReadOnly() {
        return getSourceConfig().isReadOnly();
    }

    @Override
    public boolean isWriteOnly() {
        return getSourceConfig().isWriteOnly();
    }
}
