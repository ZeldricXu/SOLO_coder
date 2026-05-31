package com.parking.platform.contract.entity;

import com.parking.platform.common.entity.BaseEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Contract extends BaseEntity {

    private String name;
    private String type;
    private String version;
    private String schema;
    private String schemaType;
    private String description;
    private List<String> tags;
    private List<ValidationResult> validationResults;
    private Map<String, Object> mockConfig;
    private boolean mockEnabled;
    private String mockEndpoint;
    private Instant lastValidatedAt;
    private String status;

    public Contract() {
        super();
        this.tags = new ArrayList<>();
        this.validationResults = new ArrayList<>();
        this.mockConfig = new HashMap<>();
        this.status = "DRAFT";
    }

    @Override
    protected String getIdPrefix() { return "contract"; }

    public static class ValidationResult {
        private String validator;
        private boolean success;
        private String message;
        private List<String> errors;
        private Instant validatedAt;

        public String getValidator() { return validator; }
        public void setValidator(String validator) { this.validator = validator; }
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public List<String> getErrors() { return errors; }
        public void setErrors(List<String> errors) { this.errors = errors; }
        public Instant getValidatedAt() { return validatedAt; }
        public void setValidatedAt(Instant validatedAt) { this.validatedAt = validatedAt; }
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = super.toMap();
        map.put("name", name);
        map.put("type", type);
        map.put("version", version);
        map.put("schema", schema);
        map.put("schemaType", schemaType);
        map.put("description", description);
        map.put("tags", tags);
        map.put("validationResults", validationResults);
        map.put("mockConfig", mockConfig);
        map.put("mockEnabled", mockEnabled);
        map.put("mockEndpoint", mockEndpoint);
        map.put("lastValidatedAt", lastValidatedAt);
        map.put("status", status);
        return map;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getSchema() { return schema; }
    public void setSchema(String schema) { this.schema = schema; }
    public String getSchemaType() { return schemaType; }
    public void setSchemaType(String schemaType) { this.schemaType = schemaType; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
    public List<ValidationResult> getValidationResults() { return validationResults; }
    public void setValidationResults(List<ValidationResult> validationResults) { this.validationResults = validationResults; }
    public Map<String, Object> getMockConfig() { return mockConfig; }
    public void setMockConfig(Map<String, Object> mockConfig) { this.mockConfig = mockConfig; }
    public boolean isMockEnabled() { return mockEnabled; }
    public void setMockEnabled(boolean mockEnabled) { this.mockEnabled = mockEnabled; }
    public String getMockEndpoint() { return mockEndpoint; }
    public void setMockEndpoint(String mockEndpoint) { this.mockEndpoint = mockEndpoint; }
    public Instant getLastValidatedAt() { return lastValidatedAt; }
    public void setLastValidatedAt(Instant lastValidatedAt) { this.lastValidatedAt = lastValidatedAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
