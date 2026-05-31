package com.web3platform.catalog.application.dto;

import com.web3platform.catalog.domain.model.DependencyRelation;
import com.web3platform.catalog.domain.model.DependencyType;

import java.util.UUID;

public class DependencyResponse {
    private UUID sourceId;
    private UUID targetId;
    private DependencyType depType;
    private String versionConstraint;

    public static DependencyResponse fromDomain(DependencyRelation relation) {
        DependencyResponse response = new DependencyResponse();
        response.sourceId = relation.getSourceId();
        response.targetId = relation.getTargetId();
        response.depType = relation.getDepType();
        response.versionConstraint = relation.getVersionConstraint();
        return response;
    }

    public UUID getSourceId() { return sourceId; }
    public UUID getTargetId() { return targetId; }
    public DependencyType getDepType() { return depType; }
    public String getVersionConstraint() { return versionConstraint; }
}
