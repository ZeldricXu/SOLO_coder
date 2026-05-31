package com.parking.platform.common.exception;

public class ConfigVersionNotFoundException extends BusinessException {

    public ConfigVersionNotFoundException(String configId, Integer version) {
        super(404, "Config version not found: config_id=" + configId + ", version=" + version);
    }

    public ConfigVersionNotFoundException(String message) {
        super(404, message);
    }
}
