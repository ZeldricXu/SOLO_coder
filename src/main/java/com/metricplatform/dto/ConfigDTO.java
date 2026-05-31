package com.metricplatform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

@Data
public class ConfigDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "命名空间不能为空")
    private String namespace;

    @NotEmpty(message = "配置参数不能为空")
    private Map<String, Object> parameters;

    private Map<String, ConfigParameterValidator> validators;

    private Boolean enabled = true;

    @Data
    public static class ConfigParameterValidator implements Serializable {
        private static final long serialVersionUID = 1L;
        private String type;
        private boolean required = false;
        private Object defaultValue;
        private String pattern;
        private Long min;
        private Long max;
        private String[] allowedValues;
    }
}
