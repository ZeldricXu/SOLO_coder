package com.parking.platform.common.entity;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class ConfigEntity extends BaseEntity {

    private String namespace;
    private Integer version;
    private Map<String, Object> parameters;
    private boolean enabled;
    private Instant appliedAt;

    public ConfigEntity() {
        super();
        this.version = 1;
        this.parameters = new HashMap<>();
        this.enabled = true;
    }

    @Override
    protected String getIdPrefix() {
        return "cfg";
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, Object> parameters) {
        this.parameters = parameters;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getAppliedAt() {
        return appliedAt;
    }

    public void setAppliedAt(Instant appliedAt) {
        this.appliedAt = appliedAt;
    }

    public Object getParameter(String key) {
        return parameters.get(key);
    }

    public Object getParameter(String key, Object defaultValue) {
        return parameters.getOrDefault(key, defaultValue);
    }

    public void setParameter(String key, Object value) {
        this.parameters.put(key, value);
    }

    public Integer getTimeout() {
        Object value = getParameter("timeout");
        return value != null ? ((Number) value).intValue() : 30;
    }

    public Integer getRetries() {
        Object value = getParameter("retries");
        return value != null ? ((Number) value).intValue() : 3;
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = super.toMap();
        map.put("config_id", getId());
        map.put("namespace", namespace);
        map.put("version", version);
        map.put("parameters", parameters);
        map.put("enabled", enabled);
        map.put("applied_at", appliedAt);
        return map;
    }
}
