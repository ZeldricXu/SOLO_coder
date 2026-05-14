package com.projmanage.model;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "risks")
public class Risk {

    @Id
    @Column(name = "risk_id", nullable = false, length = 64)
    private String riskId;

    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;

    @Column(name = "task_id", length = 64)
    private String taskId;

    @Column(name = "risk_type", nullable = false, length = 64)
    private String riskType;

    @Column(name = "risk_description", columnDefinition = "TEXT")
    private String riskDescription;

    @Column(name = "risk_level", nullable = false, length = 32)
    private String riskLevel;

    @Column(name = "risk_status", nullable = false, length = 32)
    private String riskStatus;

    @Column(name = "identified_at", nullable = false)
    private LocalDateTime identifiedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    public Risk() {
    }

    public String getRiskId() {
        return riskId;
    }

    public void setRiskId(String riskId) {
        this.riskId = riskId;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getRiskType() {
        return riskType;
    }

    public void setRiskType(String riskType) {
        this.riskType = riskType;
    }

    public String getRiskDescription() {
        return riskDescription;
    }

    public void setRiskDescription(String riskDescription) {
        this.riskDescription = riskDescription;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public String getRiskStatus() {
        return riskStatus;
    }

    public void setRiskStatus(String riskStatus) {
        this.riskStatus = riskStatus;
    }

    public LocalDateTime getIdentifiedAt() {
        return identifiedAt;
    }

    public void setIdentifiedAt(LocalDateTime identifiedAt) {
        this.identifiedAt = identifiedAt;
    }

    public LocalDateTime getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(LocalDateTime resolvedAt) {
        this.resolvedAt = resolvedAt;
    }
}
