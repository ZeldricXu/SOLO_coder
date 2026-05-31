package com.solocoder.dns.config.validator;

import com.solocoder.dns.common.entity.ConfigDefinition;
import com.solocoder.dns.common.exception.ValidationException;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class ConfigValidator {
    public void validate(ConfigDefinition config) {
        if (config == null) {
            throw new ValidationException("config", "不能为空");
        }
        if (config.getConfigId() == null || config.getConfigId().isEmpty()) {
            throw new ValidationException("configId", "不能为空");
        }
        if (config.getNamespace() == null || config.getNamespace().isEmpty()) {
            throw new ValidationException("namespace", "不能为空");
        }
        if (config.getVersion() == null || config.getVersion() < 1) {
            throw new ValidationException("version", "必须大于0");
        }
        validateParameters(config.getParameters());
    }

    private void validateParameters(Map<String, Object> parameters) {
        if (parameters == null) {
            return;
        }
        parameters.forEach((key, value) -> {
            if (key == null || key.isEmpty()) {
                throw new ValidationException("parameters key", "不能为空");
            }
        });
    }

    public void validateDnsConfig(Map<String, Object> params) {
        if (params == null) return;
        params.forEach((k, v) -> {
            switch (k) {
                case "timeout":
                case "retries":
                    if (!(v instanceof Number)) {
                        throw new ValidationException(k, "必须是数字");
                    }
                    break;
                default:
                    break;
            }
        });
    }
}
