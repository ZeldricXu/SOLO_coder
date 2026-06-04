package com.cicd.server.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "projects", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"name"})
})
public class Project extends BaseEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(name = "git_repo_url", length = 500)
    private String gitRepoUrl;

    @Column(name = "git_provider", length = 50)
    private String gitProvider;

    @Column(name = "webhook_secret", length = 200)
    private String webhookSecret;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Pipeline> pipelines;

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Deployment> deployments;

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Artifact> artifacts;

    @ManyToMany(mappedBy = "projects", fetch = FetchType.LAZY)
    private List<UserRole> userRoles;
}
