package com.web3platform.catalog.application.dto;

import com.web3platform.catalog.domain.model.DependencyType;

import java.util.UUID;

public class AddDependencyRequest {
    private UUID targetId;
    private DependencyType depType;
    private String versionConstraint;

    public UUID getTargetId() { return targetId; }
    public void setTargetId(UUID targetId) { this.targetId = targetId; }
    public DependencyType getDepType() { return depType; }
    public void setDepType(DependencyType depType) { this.depType = depType; }
    public String getVersionConstraint() { return versionConstraint; }
    public void setVersionConstraint(String versionConstraint) { this.versionConstraint = versionConstraint; }
}
