package com.datasync.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class DataVersion {

    @JsonProperty("version_id")
    private String versionId;

    @JsonProperty("data_key")
    private String dataKey;

    @JsonProperty("data_source")
    private String dataSource;

    @JsonProperty("version")
    private String version;

    @JsonProperty("checksum")
    private String checksum;

    @JsonProperty("updated_at")
    private Instant updatedAt;

    @JsonProperty("sync_id")
    private String syncId;

    @JsonProperty("task_id")
    private String taskId;

    public DataVersion() {
        this.updatedAt = Instant.now();
    }

    public String getVersionId() {
        return versionId;
    }

    public void setVersionId(String versionId) {
        this.versionId = versionId;
    }

    public String getDataKey() {
        return dataKey;
    }

    public void setDataKey(String dataKey) {
        this.dataKey = dataKey;
    }

    public String getDataSource() {
        return dataSource;
    }

    public void setDataSource(String dataSource) {
        this.dataSource = dataSource;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getSyncId() {
        return syncId;
    }

    public void setSyncId(String syncId) {
        this.syncId = syncId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String generateVersionId() {
        return "ver_" + dataKey + "_" + version;
    }

    public boolean isNewerThan(DataVersion other) {
        if (other == null) {
            return true;
        }
        return this.updatedAt.isAfter(other.updatedAt);
    }

    public boolean isSameVersion(DataVersion other) {
        if (other == null) {
            return false;
        }
        return Objects.equals(this.version, other.version);
    }

    public boolean hasSameChecksum(DataVersion other) {
        if (other == null) {
            return false;
        }
        return Objects.equals(this.checksum, other.checksum);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DataVersion that = (DataVersion) o;
        return Objects.equals(versionId, that.versionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(versionId);
    }

    @Override
    public String toString() {
        return "DataVersion{" +
                "versionId='" + versionId + '\'' +
                ", dataKey='" + dataKey + '\'' +
                ", version='" + version + '\'' +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
