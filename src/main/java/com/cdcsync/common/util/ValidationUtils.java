package com.cdcsync.common.util;

import com.cdcsync.common.exception.BusinessException;

import java.util.Collection;
import java.util.Map;
import java.util.regex.Pattern;

public class ValidationUtils {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");
    private static final Pattern SQL_INJECTION_PATTERN = Pattern.compile(".*[';\"--].*");
    private static final int MAX_IDENTIFIER_LENGTH = 128;
    private static final int MAX_SQL_LENGTH = 65536;
    private static final int MAX_LIMIT = 10000;
    private static final int MIN_LIMIT = 1;

    private ValidationUtils() {
    }

    public static void notNull(Object value, String fieldName) {
        if (value == null) {
            throw new BusinessException(400, fieldName + " cannot be null");
        }
    }

    public static void notBlank(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new BusinessException(400, fieldName + " cannot be blank");
        }
    }

    public static void notEmpty(Collection<?> collection, String fieldName) {
        if (collection == null || collection.isEmpty()) {
            throw new BusinessException(400, fieldName + " cannot be empty");
        }
    }

    public static void notEmpty(Map<?, ?> map, String fieldName) {
        if (map == null || map.isEmpty()) {
            throw new BusinessException(400, fieldName + " cannot be empty");
        }
    }

    public static void isTrue(boolean condition, String message) {
        if (!condition) {
            throw new BusinessException(400, message);
        }
    }

    public static void isPositive(Integer value, String fieldName) {
        if (value == null || value <= 0) {
            throw new BusinessException(400, fieldName + " must be positive");
        }
    }

    public static void isPositive(Long value, String fieldName) {
        if (value == null || value <= 0) {
            throw new BusinessException(400, fieldName + " must be positive");
        }
    }

    public static void inRange(int value, int min, int max, String fieldName) {
        if (value < min || value > max) {
            throw new BusinessException(400, fieldName + " must be between " + min + " and " + max);
        }
    }

    public static void maxLength(String value, int maxLength, String fieldName) {
        if (value != null && value.length() > maxLength) {
            throw new BusinessException(400, fieldName + " exceeds maximum length of " + maxLength);
        }
    }

    public static void validIdentifier(String identifier, String fieldName) {
        notBlank(identifier, fieldName);
        maxLength(identifier, MAX_IDENTIFIER_LENGTH, fieldName);
        if (!IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new BusinessException(400, fieldName + " contains invalid characters");
        }
    }

    public static void validSqlIdentifier(String identifier, String fieldName) {
        notBlank(identifier, fieldName);
        maxLength(identifier, MAX_IDENTIFIER_LENGTH, fieldName);
        if (SQL_INJECTION_PATTERN.matcher(identifier).matches()) {
            throw new BusinessException(400, fieldName + " contains potential SQL injection characters");
        }
    }

    public static void validSqlLength(String sql, String fieldName) {
        notBlank(sql, fieldName);
        maxLength(sql, MAX_SQL_LENGTH, fieldName);
    }

    public static int validLimit(int limit) {
        if (limit < MIN_LIMIT) {
            return MIN_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    public static void validPort(int port) {
        inRange(port, 1, 65535, "port");
    }

    @SuppressWarnings("unchecked")
    public static <T> T safeCast(Object value, Class<T> clazz, String fieldName) {
        if (value == null) {
            return null;
        }
        if (!clazz.isInstance(value)) {
            throw new BusinessException(400, fieldName + " must be of type " + clazz.getSimpleName());
        }
        return (T) value;
    }

    public static String safeTrim(String value) {
        return value == null ? null : value.trim();
    }

    public static String safeTruncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
