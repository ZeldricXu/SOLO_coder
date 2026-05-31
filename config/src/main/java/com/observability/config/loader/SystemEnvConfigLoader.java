package com.observability.config.loader;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class SystemEnvConfigLoader implements ConfigLoader {

    @Override
    public String getSource() {
        return "system_env";
    }

    @Override
    public Map<String, Object> load(String namespace) {
        Map<String, Object> result = new HashMap<>();
        String prefix = namespace.toUpperCase().replace("-", "_") + "_";
        Map<String, String> env = System.getenv();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                String key = entry.getKey().substring(prefix.length()).toLowerCase();
                result.put(key, entry.getValue());
            }
        }
        log.info("Loaded {} configs from system env for namespace: {}", result.size(), namespace);
        return result;
    }

    @Override
    public int getOrder() {
        return 50;
    }
}
