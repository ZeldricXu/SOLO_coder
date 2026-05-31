package com.tracetopology.core.validation;

import com.tracetopology.common.exception.ValidationException;

import java.util.Map;

public class ParamValidator {

    public static void validateParams(Map<String, Object> params, String... requiredFields) {
        if (params == null) {
            throw new ValidationException("params", "参数不能为空");
        }
        for (String field : requiredFields) {
            if (!params.containsKey(field) || params.get(field) == null) {
                throw new ValidationException(field, "必填参数缺失");
            }
        }
    }

    public static void validateNotNull(Object value, String fieldName) {
        if (value == null) {
            throw new ValidationException(fieldName, "不能为空");
        }
    }

    public static void validateNotBlank(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new ValidationException(fieldName, "不能为空或空白");
        }
    }

    public static void validatePositive(Number value, String fieldName) {
        if (value == null || value.doubleValue() <= 0) {
            throw new ValidationException(fieldName, "必须为正数");
        }
    }

    public static void validateRange(Number value, Number min, Number max, String fieldName) {
        if (value == null) {
            throw new ValidationException(fieldName, "不能为空");
        }
        double val = value.doubleValue();
        if (val < min.doubleValue() || val > max.doubleValue()) {
            throw new ValidationException(fieldName,
                    String.format("必须在 %s 到 %s 之间", min, max));
        }
    }
}
