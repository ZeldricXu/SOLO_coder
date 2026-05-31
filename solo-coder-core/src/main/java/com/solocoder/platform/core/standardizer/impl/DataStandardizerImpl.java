package com.solocoder.platform.core.standardizer.impl;

import com.solocoder.platform.core.model.DataRecord;
import com.solocoder.platform.core.model.StandardizationRule;
import com.solocoder.platform.core.standardizer.DataStandardizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class DataStandardizerImpl implements DataStandardizer {

    @Override
    public DataRecord standardize(DataRecord record, StandardizationRule rule) {
        if (record == null || record.getFields() == null) return record;
        Map<String, Object> fields = new HashMap<>(record.getFields());
        Object value = fields.get(rule.getField());
        if (value == null) return record;

        switch (rule.getType()) {
            case DATE_FORMAT -> {
                String pattern = (String) rule.getParameters().getOrDefault("pattern", "yyyy-MM-dd");
                try {
                    if (value instanceof java.time.temporal.Temporal temporal) {
                        fields.put(rule.getField(), DateTimeFormatter.ofPattern(pattern).format(temporal));
                    } else {
                        fields.put(rule.getField(), value.toString());
                    }
                } catch (Exception e) {
                    log.warn("Date format failed: field={}, value={}", rule.getField(), value);
                }
            }
            case NUMBER_SCALE -> {
                int scale = ((Number) rule.getParameters().getOrDefault("scale", 2)).intValue();
                RoundingMode mode = RoundingMode.valueOf(
                        (String) rule.getParameters().getOrDefault("roundingMode", "HALF_UP"));
                try {
                    BigDecimal bd = new BigDecimal(value.toString());
                    fields.put(rule.getField(), bd.setScale(scale, mode));
                } catch (NumberFormatException e) {
                    log.warn("Number scale failed: field={}, value={}", rule.getField(), value);
                }
            }
            case STRING_CASE -> {
                String casing = (String) rule.getParameters().getOrDefault("case", "lower");
                fields.put(rule.getField(), switch (casing) {
                    case "upper" -> value.toString().toUpperCase();
                    case "lower" -> value.toString().toLowerCase();
                    case "capitalize" -> value.toString().substring(0, 1).toUpperCase() + value.toString().substring(1).toLowerCase();
                    default -> value.toString();
                });
            }
            case TRIM_WHITESPACE -> fields.put(rule.getField(), value.toString().trim());
            case NULL_DEFAULT -> {
                if (value == null || value.toString().isBlank()) {
                    fields.put(rule.getField(), rule.getParameters().get("defaultValue"));
                }
            }
            case ENUM_MAPPING -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> mappings = (Map<String, Object>) rule.getParameters().get("mappings");
                if (mappings != null && mappings.containsKey(value.toString())) {
                    fields.put(rule.getField(), mappings.get(value.toString()));
                }
            }
            case REGEX_REPLACE -> {
                String regex = (String) rule.getParameters().get("regex");
                String replacement = (String) rule.getParameters().get("replacement");
                if (regex != null && replacement != null) {
                    fields.put(rule.getField(), Pattern.compile(regex).matcher(value.toString()).replaceAll(replacement));
                }
            }
            default -> log.warn("Unknown standardization type: {}", rule.getType());
        }

        record.setFields(fields);
        return record;
    }

    @Override
    public List<DataRecord> standardizeBatch(List<DataRecord> records, List<StandardizationRule> rules) {
        return records.stream()
                .map(record -> {
                    DataRecord current = record;
                    for (StandardizationRule rule : rules) {
                        current = standardize(current, rule);
                    }
                    return current;
                })
                .collect(Collectors.toList());
    }
}
