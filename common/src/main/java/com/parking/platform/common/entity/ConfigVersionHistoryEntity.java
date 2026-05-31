package com.parking.platform.common.entity;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class ConfigVersionHistoryEntity extends BaseEntity {

    private String configId;
    private Integer version;
    private Map<String, Object> parameters;
    private String changeReason;
    private String changedBy;
    private Instant appliedAt;
    private boolean isRollbackPoint;
    private String rollbackComment;

    public ConfigVersionHistoryEntity() {
        super();
        this.parameters = new HashMap<>();
        this.isRollbackPoint = false;
    }

    public ConfigVersionHistoryEntity(String configId, Integer version) {
        this();
        this.configId = configId;
        this.version = version;
    }

    @Override
    protected String getIdPrefix() {
        return "hist";
    }

    public String getConfigId() {
        return configId;
    }

    public void setConfigId(String configId) {
        this.configId = configId;
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

    public String getChangeReason() {
        return changeReason;
    }

    public void setChangeReason(String changeReason) {
        this.changeReason = changeReason;
    }

    public String getChangedBy() {
        return changedBy;
    }

    public void setChangedBy(String changedBy) {
        this.changedBy = changedBy;
    }

    public Instant getAppliedAt() {
        return appliedAt;
    }

    public void setAppliedAt(Instant appliedAt) {
        this.appliedAt = appliedAt;
    }

    public boolean isRollbackPoint() {
        return isRollbackPoint;
    }

    public void setRollbackPoint(boolean rollbackPoint) {
        isRollbackPoint = rollbackPoint;
    }

    public String getRollbackComment() {
        return rollbackComment;
    }

    public void setRollbackComment(String rollbackComment) {
        this.rollbackComment = rollbackComment;
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = super.toMap();
        map.put("history_id", getId());
        map.put("config_id", configId);
        map.put("version", version);
        map.put("parameters", parameters);
        map.put("change_reason", changeReason);
        map.put("changed_by", changedBy);
        map.put("applied_at", appliedAt);
        map.put("is_rollback_point", isRollbackPoint);
        map.put("rollback_comment", rollbackComment);
        return map;
    }
}
