package com.observability.config.loader;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.observability.common.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class FileConfigLoader implements ConfigLoader {

    @Value("${observability.config.path:./config}")
    private String configPath;

    @Override
    public String getSource() {
        return "file";
    }

    @Override
    public Map<String, Object> load(String namespace) {
        Map<String, Object> result = new HashMap<>();
        try {
            File configFile = new File(configPath, namespace + ".json");
            if (configFile.exists()) {
                String content = FileUtil.readUtf8String(configFile);
                if (StrUtil.isNotBlank(content)) {
                    Map<String, Object> config = JsonUtil.toMap(content);
                    result.putAll(config);
                }
            }
            log.info("Loaded {} configs from file for namespace: {}", result.size(), namespace);
        } catch (Exception e) {
            log.error("Failed to load config from file for namespace: {}", namespace, e);
        }
        return result;
    }

    @Override
    public int getOrder() {
        return 200;
    }
}
