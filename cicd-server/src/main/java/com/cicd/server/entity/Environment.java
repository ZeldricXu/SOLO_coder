package com.cicd.server.entity;

import com.cicd.common.enums.ApprovalMode;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "environments")
public class Environment extends BaseEntity {

    @Column(nullable = false, length = 50)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(name = "namespace", length = 100)
    private String namespace;

    @Column(name = "cluster_name", length = 100)
    private String clusterName;

    @Column(name = "kubeconfig_secret", length = 200)
    private String kubeconfigSecret;

    @Column(name = "ingress_domain", length = 200)
    private String ingressDomain;

    @Column(name = "requires_approval", nullable = false)
    private Boolean requiresApproval = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_mode", length = 20)
    private ApprovalMode approvalMode;

    @Column(name = "approvers_json", columnDefinition = "TEXT")
    private String approversJson;

    @Column(name = "is_protected", nullable = false)
    private Boolean isProtected = false;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @OneToMany(mappedBy = "environment", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<EnvironmentVariable> variables;

    @OneToMany(mappedBy = "environment", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Deployment> deployments;
}
