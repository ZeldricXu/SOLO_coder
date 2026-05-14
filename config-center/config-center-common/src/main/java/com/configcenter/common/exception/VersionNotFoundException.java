package com.configcenter.common.exception;

public class VersionNotFoundException extends BusinessException {

    public VersionNotFoundException(String configId, String version) {
        super(404, "版本不存在: configId=" + configId + ", version=" + version);
    }
}
