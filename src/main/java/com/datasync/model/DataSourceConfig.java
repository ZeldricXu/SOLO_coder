package com.datasync.model;

import com.datasync.common.Constants;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class DataSourceConfig {

    @JsonProperty("source_id")
    private String sourceId;

    @JsonProperty("source_type")
    private String sourceType;

    @JsonProperty("connection")
    private Map<String, Object> connection;

    @JsonProperty("status")
    private String status;

    @JsonProperty("created_at")
    private Instant createdAt;

    @JsonProperty("updated_at")
    private Instant updatedAt;

    @JsonIgnore
    private String password;

    public DataSourceConfig() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.status = Constants.DATA_SOURCE_STATUS_ACTIVE;
    }

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public Map<String, Object> getConnection() {
        return connection;
    }

    public void setConnection(Map<String, Object> connection) {
        this.connection = connection;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getHost() {
        if (connection != null && connection.containsKey("host")) {
            return String.valueOf(connection.get("host"));
        }
        return null;
    }

    public Integer getPort() {
        if (connection != null && connection.containsKey("port")) {
            Object port = connection.get("port");
            if (port instanceof Number) {
                return ((Number) port).intValue();
            }
        }
        return null;
    }

    public String getDatabase() {
        if (connection != null && connection.containsKey("database")) {
            return String.valueOf(connection.get("database"));
        }
        return null;
    }

    public String getUser() {
        if (connection != null && connection.containsKey("user")) {
            return String.valueOf(connection.get("user"));
        }
        return null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DataSourceConfig that = (DataSourceConfig) o;
        return Objects.equals(sourceId, that.sourceId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceId);
    }

    @Override
    public String toString() {
        return "DataSourceConfig{" +
                "sourceId='" + sourceId + '\'' +
                ", sourceType='" + sourceType + '\'' +
                ", status='" + status + '\'' +
                '}';
    }
}
