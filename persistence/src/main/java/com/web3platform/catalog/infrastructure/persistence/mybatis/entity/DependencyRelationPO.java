package com.web3platform.catalog.infrastructure.persistence.mybatis.entity;

import com.web3platform.catalog.domain.model.DependencyRelation;
import com.web3platform.catalog.domain.model.DependencyType;

import java.util.UUID;

public class DependencyRelationPO {
    private String sourceId;
    private String targetId;
    private String depType;
    private String versionConstraint;

    public static DependencyRelationPO fromDomain(DependencyRelation relation) {
        DependencyRelationPO po = new DependencyRelationPO();
        po.sourceId = relation.getSourceId().toString();
        po.targetId = relation.getTargetId().toString();
        po.depType = relation.getDepType().name();
        po.versionConstraint = relation.getVersionConstraint();
        return po;
    }

    public DependencyRelation toDomain() {
        return new DependencyRelation(
            UUID.fromString(sourceId),
            UUID.fromString(targetId),
            DependencyType.valueOf(depType),
            versionConstraint
        );
    }

    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public String getDepType() { return depType; }
    public void setDepType(String depType) { this.depType = depType; }
    public String getVersionConstraint() { return versionConstraint; }
    public void setVersionConstraint(String versionConstraint) { this.versionConstraint = versionConstraint; }
}
