package com.observability.config.loader;

import com.observability.common.entity.ConfigEntity;
import com.observability.dal.mapper.ConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseConfigLoader implements ConfigLoader {

    private final ConfigMapper configMapper;

    @Override
    public String getSource() {
        return "database";
    }

    @Override
    public Map<String, Object> load(String namespace) {
        Map<String, Object> result = new HashMap<>();
        try {
            List<ConfigEntity> configs = configMapper.findAllByNamespace(namespace);
            for (ConfigEntity config : configs) {
                if (config.getParameters() != null) {
                    result.putAll(config.getParameters());
                }
            }
            log.info("Loaded {} configs from database for namespace: {}", result.size(), namespace);
        } catch (Exception e) {
            log.error("Failed to load config from database for namespace: {}", namespace, e);
        }
        return result;
    }

    @Override
    public int getOrder() {
        return 100;
    }
}
