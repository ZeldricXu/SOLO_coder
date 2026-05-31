package com.taskplatform.config.source;

import com.taskplatform.config.ConfigSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EnvironmentConfigSource implements ConfigSource {

    private final Environment environment;

    @Override
    public String getName() {
        return "environment";
    }

    @Override
    public String getValue(String namespace, String key) {
        String envKey = namespace.toUpperCase().replace('.', '_') + "_" + key.toUpperCase().replace('.', '_');
        return environment.getProperty(envKey);
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public int getPriority() {
        return 200;
    }
}
