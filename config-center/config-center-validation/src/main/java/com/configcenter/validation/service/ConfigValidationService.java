package com.configcenter.validation.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONException;
import com.configcenter.common.enums.ConfigType;
import com.configcenter.common.exception.ConfigValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ConfigValidationService {

    public void validate(String value, ConfigType type) {
        if (value == null) {
            throw new ConfigValidationException("配置值不能为空");
        }

        switch (type) {
            case STRING:
                validateString(value);
                break;
            case NUMBER:
                validateNumber(value);
                break;
            case BOOLEAN:
                validateBoolean(value);
                break;
            case JSON:
                validateJson(value);
                break;
            case YAML:
                validateYaml(value);
                break;
            case XML:
                validateXml(value);
                break;
            default:
                throw new ConfigValidationException("不支持的配置类型: " + type);
        }
    }

    private void validateString(String value) {
        if (value.isEmpty()) {
            throw new ConfigValidationException("字符串配置值不能为空");
        }
    }

    private void validateNumber(String value) {
        try {
            if (value.contains(".") || value.toLowerCase().contains("e")) {
                Double.parseDouble(value);
            } else {
                Long.parseLong(value);
            }
        } catch (NumberFormatException e) {
            throw new ConfigValidationException("数值格式错误: " + value, e);
        }
    }

    private void validateBoolean(String value) {
        String lower = value.toLowerCase();
        if (!"true".equals(lower) && !"false".equals(lower)) {
            throw new ConfigValidationException("布尔值格式错误，只能是true或false: " + value);
        }
    }

    private void validateJson(String value) {
        try {
            JSON.parse(value);
        } catch (JSONException e) {
            throw new ConfigValidationException("JSON格式错误: " + e.getMessage(), e);
        }
    }

    private void validateYaml(String value) {
        if (value == null || value.isEmpty()) {
            throw new ConfigValidationException("YAML配置值不能为空");
        }
        if (value.contains("\t")) {
            throw new ConfigValidationException("YAML格式错误：不能使用制表符，必须使用空格缩进");
        }
    }

    private void validateXml(String value) {
        try {
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(value.getBytes()));
        } catch (Exception e) {
            throw new ConfigValidationException("XML格式错误: " + e.getMessage(), e);
        }
    }

    public boolean isValid(String value, ConfigType type) {
        try {
            validate(value, type);
            return true;
        } catch (ConfigValidationException e) {
            log.debug("Validation failed: {}", e.getMessage());
            return false;
        }
    }

    public void validateKey(String configKey) {
        if (configKey == null || configKey.isEmpty()) {
            throw new ConfigValidationException("配置键不能为空");
        }
        if (configKey.length() > 255) {
            throw new ConfigValidationException("配置键长度不能超过255个字符");
        }
        if (!configKey.matches("^[a-zA-Z][a-zA-Z0-9._-]*$")) {
            throw new ConfigValidationException("配置键格式错误，只能以字母开头，只能包含字母、数字、点、下划线和连字符");
        }
    }

    public void validateDescription(String description) {
        if (description != null && description.length() > 500) {
            throw new ConfigValidationException("描述长度不能超过500个字符");
        }
    }
}
