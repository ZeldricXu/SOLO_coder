package com.edgeplatform.common.util;

import com.edgeplatform.common.exception.ValidationException;
import org.apache.commons.lang3.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class ValidationUtils {

    private static final Pattern ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{3,64}$");
    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_-]{1,62}[a-zA-Z0-9]$");

    private ValidationUtils() {
    }

    public static void validateId(String id, String fieldName) {
        Map<String, String> errors = new HashMap<>();
        if (StringUtils.isBlank(id)) {
            errors.put(fieldName, "must not be blank");
            throw new ValidationException("Validation failed", errors);
        }
        if (!ID_PATTERN.matcher(id).matches()) {
            errors.put(fieldName, "must match pattern: " + ID_PATTERN.pattern());
            throw new ValidationException("Validation failed", errors);
        }
    }

    public static void validateNamespace(String namespace) {
        Map<String, String> errors = new HashMap<>();
        if (StringUtils.isBlank(namespace)) {
            errors.put("namespace", "must not be blank");
            throw new ValidationException("Validation failed", errors);
        }
        if (!NAMESPACE_PATTERN.matcher(namespace).matches()) {
            errors.put("namespace", "must match pattern: " + NAMESPACE_PATTERN.pattern());
            throw new ValidationException("Validation failed", errors);
        }
    }

    public static void notNull(Object value, String fieldName) {
        if (value == null) {
            Map<String, String> errors = new HashMap<>();
            errors.put(fieldName, "must not be null");
            throw new ValidationException("Validation failed", errors);
        }
    }

    public static void notBlank(String value, String fieldName) {
        if (StringUtils.isBlank(value)) {
            Map<String, String> errors = new HashMap<>();
            errors.put(fieldName, "must not be blank");
            throw new ValidationException("Validation failed", errors);
        }
    }

    public static void isTrue(boolean condition, String fieldName, String message) {
        if (!condition) {
            Map<String, String> errors = new HashMap<>();
            errors.put(fieldName, message);
            throw new ValidationException("Validation failed", errors);
        }
    }
}
