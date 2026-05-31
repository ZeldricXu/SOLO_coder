package com.taskflow.common.utils;

import com.taskflow.common.exception.BusinessException;
import com.taskflow.common.exception.ValidationException;

import java.util.Map;

public class AssertUtils {

    public static void notNull(Object obj, String message) {
        if (obj == null) {
            throw new ValidationException(message);
        }
    }

    public static void notBlank(String str, String message) {
        if (str == null || str.trim().isEmpty()) {
            throw new ValidationException(message);
        }
    }

    public static void notEmpty(Map<?, ?> map, String message) {
        if (map == null || map.isEmpty()) {
            throw new ValidationException(message);
        }
    }

    public static void isTrue(boolean condition, String message) {
        if (!condition) {
            throw new ValidationException(message);
        }
    }

    public static void isTrue(boolean condition, int code, String message) {
        if (!condition) {
            throw new BusinessException(code, message);
        }
    }
}
