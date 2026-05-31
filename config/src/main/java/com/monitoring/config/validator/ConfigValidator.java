package com.monitoring.config.validator;

import com.monitoring.common.exception.ValidationException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class ConfigValidator {

    public ValidationResult validate(Map<String, Object> config, ConfigSchema schema) {
        Map<String, String> errors = new HashMap<>();

        for (ConfigField field : schema.getFields()) {
            Object value = config.get(field.getName());

            if (field.isRequired() && value == null) {
                errors.put(field.getName(), "Field is required");
                continue;
            }

            if (value != null) {
                if (!field.getType().isInstance(value)) {
                    errors.put(field.getName(), "Invalid type, expected: " + field.getType().getSimpleName());
                }

                if (field.getValidator() != null) {
                    String validationError = field.getValidator().validate(value);
                    if (validationError != null) {
                        errors.put(field.getName(), validationError);
                    }
                }
            }
        }

        return new ValidationResult(errors.isEmpty(), errors);
    }

    public void validateOrThrow(Map<String, Object> config, ConfigSchema schema) {
        ValidationResult result = validate(config, schema);
        if (!result.isValid()) {
            throw new ValidationException("Configuration validation failed", result.getErrors());
        }
    }

    public record ValidationResult(boolean valid, Map<String, String> errors) {
    }

    public interface FieldValidator {
        String validate(Object value);
    }

    public static class ConfigSchema {
        private final java.util.List<ConfigField> fields;

        public ConfigSchema(java.util.List<ConfigField> fields) {
            this.fields = fields;
        }

        public java.util.List<ConfigField> getFields() {
            return fields;
        }
    }

    public static class ConfigField {
        private final String name;
        private final Class<?> type;
        private final boolean required;
        private final Object defaultValue;
        private final FieldValidator validator;

        public ConfigField(String name, Class<?> type, boolean required, Object defaultValue, FieldValidator validator) {
            this.name = name;
            this.type = type;
            this.required = required;
            this.defaultValue = defaultValue;
            this.validator = validator;
        }

        public String getName() { return name; }
        public Class<?> getType() { return type; }
        public boolean isRequired() { return required; }
        public Object getDefaultValue() { return defaultValue; }
        public FieldValidator getValidator() { return validator; }
    }
}
