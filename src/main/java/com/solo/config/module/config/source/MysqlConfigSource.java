package com.solo.config.module.config.source;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.solo.config.entity.Config;
import com.solo.config.mapper.ConfigMapper;
import com.solo.config.module.config.ConfigSourceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class MysqlConfigSource extends AbstractConfigSource {

    private final ConfigMapper configMapper;

    public MysqlConfigSource(ConfigMapper configMapper, ConfigSourceProperties properties) {
        super(properties);
        this.configMapper = configMapper;
    }

    @Override
    public String getType() {
        return "mysql";
    }

    @Override
    public int getPriority() {
        return 1;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String getConfig(String namespace, String key) {
        try {
            Config config = configMapper.selectOne(
                    new QueryWrapper<Config>()
                            .eq("namespace", namespace)
                            .eq("enabled", true)
                            .orderByDesc("version")
                            .last("LIMIT 1")
            );
            if (config != null && config.getParameters() != null) {
                Object value = config.getParameters().get(key);
                return value != null ? value.toString() : null;
            }
        } catch (Exception e) {
            log.error("Failed to get config from mysql, namespace: {}, key: {}", namespace, key, e);
        }
        return null;
    }

    @Override
    public void setConfig(String namespace, String key, String value) {
        try {
            Config existing = configMapper.selectOne(
                    new QueryWrapper<Config>()
                            .eq("namespace", namespace)
                            .eq("enabled", true)
                            .orderByDesc("version")
                            .last("LIMIT 1")
            );

            int newVersion = existing != null ? existing.getVersion() + 1 : 1;
            Map<String, Object> parameters = existing != null ?
                    new java.util.HashMap<>(existing.getParameters()) : new java.util.HashMap<>();
            parameters.put(key, value);

            Config config = new Config();
            config.setConfigId("cfg_" + namespace + "_" + newVersion);
            config.setNamespace(namespace);
            config.setVersion(newVersion);
            config.setParameters(parameters);
            config.setEnabled(true);
            config.setSourceType(getType());
            config.setAppliedAt(java.time.LocalDateTime.now());

            configMapper.insert(config);
            log.info("Config saved to mysql, namespace: {}, key: {}, version: {}", namespace, key, newVersion);
        } catch (Exception e) {
            log.error("Failed to set config to mysql, namespace: {}, key: {}", namespace, key, e);
        }
    }

    @Override
    public void refresh() {
        log.debug("Mysql config source refreshed");
    }
}
