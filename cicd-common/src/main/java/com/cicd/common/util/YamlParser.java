package com.cicd.common.util;

import com.cicd.common.dto.pipeline.PipelineDefinition;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class YamlParser {

    private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
        .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);

    private static final Validator validator;

    static {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    public static PipelineDefinition parse(String yamlContent) throws PipelineValidationException {
        try {
            PipelineDefinition definition = yamlMapper.readValue(yamlContent, PipelineDefinition.class);
            validate(definition);
            return definition;
        } catch (Exception e) {
            if (e instanceof PipelineValidationException pve) {
                throw pve;
            }
            throw new PipelineValidationException("Failed to parse pipeline YAML: " + e.getMessage(), e);
        }
    }

    public static PipelineDefinition parse(InputStream inputStream) throws PipelineValidationException {
        try {
            PipelineDefinition definition = yamlMapper.readValue(inputStream, PipelineDefinition.class);
            validate(definition);
            return definition;
        } catch (Exception e) {
            if (e instanceof PipelineValidationException pve) {
                throw pve;
            }
            throw new PipelineValidationException("Failed to parse pipeline YAML: " + e.getMessage(), e);
        }
    }

    public static String dump(PipelineDefinition definition) {
        try {
            return yamlMapper.writeValueAsString(definition);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize pipeline definition", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseToMap(String yamlContent) {
        try {
            return yamlMapper.readValue(yamlContent, Map.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse YAML to map: " + e.getMessage(), e);
        }
    }

    public static String substituteVariables(String content, Map<String, String> variables) {
        String result = content;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String placeholder = "${" + entry.getKey() + "}";
            result = result.replace(placeholder, entry.getValue());
        }
        return result;
    }

    private static void validate(PipelineDefinition definition) throws PipelineValidationException {
        Set<ConstraintViolation<PipelineDefinition>> violations = validator.validate(definition);
        if (!violations.isEmpty()) {
            List<ValidationError> errors = violations.stream()
                .map(v -> new ValidationError(
                    v.getPropertyPath().toString(),
                    v.getMessage(),
                    v.getInvalidValue() != null ? v.getInvalidValue().toString() : null
                ))
                .collect(Collectors.toList());
            throw new PipelineValidationException("Pipeline validation failed", errors);
        }
    }

    public static class PipelineValidationException extends Exception {
        private final List<ValidationError> errors;

        public PipelineValidationException(String message, List<ValidationError> errors) {
            super(message);
            this.errors = errors;
        }

        public PipelineValidationException(String message, Throwable cause) {
            super(message, cause);
            this.errors = new ArrayList<>();
        }

        public List<ValidationError> getErrors() {
            return errors;
        }
    }

    public record ValidationError(
        String field,
        String message,
        String invalidValue
    ) {}
}
