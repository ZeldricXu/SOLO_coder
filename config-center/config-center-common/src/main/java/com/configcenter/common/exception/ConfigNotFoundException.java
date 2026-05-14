package com.configcenter.common.exception;

public class ConfigNotFoundException extends BusinessException {

    public ConfigNotFoundException(String configId) {
        super(404, "配置不存在: " + configId);
    }

    public ConfigNotFoundException(String configKey, String groupId) {
        super(404, "配置不存在: configKey=" + configKey + ", groupId=" + groupId);
    }
}
