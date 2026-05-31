package com.datastandard.modules.core;

import com.datastandard.modules.core.dto.StandardizationConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataTypeConverter {

    private final MeterRegistry meterRegistry;

    private final Map<String, DateTimeFormatter> formatterCache = new ConcurrentHashMap<>();

    public Mono<Map<String, Object>> convert(Map<String, Object> record, StandardizationConfig.FieldRule rule, ProcessingContext context) {
        return Mono.fromCallable(() -> {
            Timer.Sample sample = Timer.start(meterRegistry);
            try {
                String sourceField = rule.getSourceField();
                String targetType = rule.getTargetType();

                if (!record.containsKey(sourceField) || targetType == null) {
                    return record;
                }

                Object value = record.get(sourceField);
                if (value == null) {
                    return record;
                }

                Object convertedValue = convertValue(value, targetType, rule);
                record.put(rule.getTargetField(), convertedValue);

                meterRegistry.counter("data.type.conversion.success",
                        "source_field", sourceField,
                        "target_type", targetType).increment();

                return record;
            } catch (Exception e) {
                log.warn("类型转换失败: field={}, type={}", rule.getSourceField(), rule.getTargetType(), e);
                context.addError(rule.getSourceField(), String.valueOf(record.get(rule.getSourceField())),
                        "TYPE_CONVERSION_ERROR", "类型转换失败: " + e.getMessage());
                meterRegistry.counter("data.type.conversion.failure",
                        "source_field", rule.getSourceField(),
                        "target_type", rule.getTargetType()).increment();
                return record;
            } finally {
                sample.stop(meterRegistry.timer("data.type.conversion.duration",
                        "target_type", rule.getTargetType()));
            }
        });
    }

    private Object convertValue(Object value, String targetType, StandardizationConfig.FieldRule rule) {
        return switch (targetType.toLowerCase()) {
            case "string" -> convertToString(value);
            case "integer", "int" -> convertToInteger(value);
            case "long" -> convertToLong(value);
            case "float" -> convertToFloat(value);
            case "double" -> convertToDouble(value);
            case "decimal", "bigdecimal" -> convertToBigDecimal(value);
            case "boolean", "bool" -> convertToBoolean(value);
            case "date" -> convertToDate(value, rule.getDateFormat());
            case "datetime" -> convertToDateTime(value, rule.getDateFormat());
            case "instant", "timestamp" -> convertToInstant(value, rule.getDateFormat());
            default -> value;
        };
    }

    private String convertToString(Object value) {
        if (value instanceof String) {
            return (String) value;
        }
        return String.valueOf(value);
    }

    private Integer convertToInteger(Object value) {
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String str) {
            return Integer.parseInt(str.trim());
        }
        throw new IllegalArgumentException("无法转换为Integer: " + value);
    }

    private Long convertToLong(Object value) {
        if (value instanceof Long) {
            return (Long) value;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String str) {
            return Long.parseLong(str.trim());
        }
        throw new IllegalArgumentException("无法转换为Long: " + value);
    }

    private Float convertToFloat(Object value) {
        if (value instanceof Float) {
            return (Float) value;
        }
        if (value instanceof Number) {
            return ((Number) value).floatValue();
        }
        if (value instanceof String str) {
            return Float.parseFloat(str.trim());
        }
        throw new IllegalArgumentException("无法转换为Float: " + value);
    }

    private Double convertToDouble(Object value) {
        if (value instanceof Double) {
            return (Double) value;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String str) {
            return Double.parseDouble(str.trim());
        }
        throw new IllegalArgumentException("无法转换为Double: " + value);
    }

    private BigDecimal convertToBigDecimal(Object value) {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return new BigDecimal(value.toString());
        }
        if (value instanceof String str) {
            return new BigDecimal(str.trim());
        }
        throw new IllegalArgumentException("无法转换为BigDecimal: " + value);
    }

    private Boolean convertToBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String str) {
            String lower = str.trim().toLowerCase();
            if ("true".equals(lower) || "1".equals(lower) || "yes".equals(lower) || "y".equals(lower)) {
                return true;
            }
            if ("false".equals(lower) || "0".equals(lower) || "no".equals(lower) || "n".equals(lower)) {
                return false;
            }
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        throw new IllegalArgumentException("无法转换为Boolean: " + value);
    }

    private LocalDate convertToDate(Object value, String format) {
        if (value instanceof LocalDate) {
            return (LocalDate) value;
        }
        if (value instanceof LocalDateTime) {
            return ((LocalDateTime) value).toLocalDate();
        }
        if (value instanceof String str) {
            DateTimeFormatter formatter = getFormatter(format != null ? format : "yyyy-MM-dd");
            return LocalDate.parse(str.trim(), formatter);
        }
        throw new IllegalArgumentException("无法转换为Date: " + value);
    }

    private LocalDateTime convertToDateTime(Object value, String format) {
        if (value instanceof LocalDateTime) {
            return (LocalDateTime) value;
        }
        if (value instanceof LocalDate) {
            return ((LocalDate) value).atStartOfDay();
        }
        if (value instanceof String str) {
            DateTimeFormatter formatter = getFormatter(format != null ? format : "yyyy-MM-dd HH:mm:ss");
            return LocalDateTime.parse(str.trim(), formatter);
        }
        throw new IllegalArgumentException("无法转换为DateTime: " + value);
    }

    private Instant convertToInstant(Object value, String format) {
        if (value instanceof Instant) {
            return (Instant) value;
        }
        if (value instanceof String str) {
            String trimmed = str.trim();
            try {
                return Instant.parse(trimmed);
            } catch (DateTimeParseException e) {
                if (format != null) {
                    DateTimeFormatter formatter = getFormatter(format);
                    return LocalDateTime.parse(trimmed, formatter).atZone(java.time.ZoneId.systemDefault()).toInstant();
                }
                throw e;
            }
        }
        if (value instanceof Number) {
            return Instant.ofEpochMilli(((Number) value).longValue());
        }
        throw new IllegalArgumentException("无法转换为Instant: " + value);
    }

    private DateTimeFormatter getFormatter(String pattern) {
        return formatterCache.computeIfAbsent(pattern, DateTimeFormatter::ofPattern);
    }
}
