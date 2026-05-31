package com.web3platform.catalog.domain.model;

import java.util.UUID;

public class DependencyRelation {
    private final UUID sourceId;
    private final UUID targetId;
    private final DependencyType depType;
    private final String versionConstraint;

    public DependencyRelation(UUID sourceId, UUID targetId, DependencyType depType, String versionConstraint) {
        this.sourceId = sourceId;
        this.targetId = targetId;
        this.depType = depType;
        this.versionConstraint = versionConstraint;
    }

    public UUID getSourceId() { return sourceId; }
    public UUID getTargetId() { return targetId; }
    public DependencyType getDepType() { return depType; }
    public String getVersionConstraint() { return versionConstraint; }
}
