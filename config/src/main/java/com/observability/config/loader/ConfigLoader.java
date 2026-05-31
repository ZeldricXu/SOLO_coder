package com.observability.config.loader;

import com.observability.common.entity.ConfigEntity;

import java.util.List;
import java.util.Map;

public interface ConfigLoader {

    String getSource();

    Map<String, Object> load(String namespace);

    default boolean supports(String namespace) {
        return true;
    }

    default int getOrder() {
        return 0;
    }
}
