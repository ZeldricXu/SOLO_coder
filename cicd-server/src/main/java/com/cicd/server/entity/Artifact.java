package com.cicd.server.entity;

import com.cicd.common.enums.ArtifactType;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "artifacts")
public class Artifact extends BaseEntity {

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ArtifactType type;

    @Column(nullable = false, length = 200)
    private String version;

    @Column(name = "full_path", length = 500)
    private String fullPath;

    @Column(length = 500)
    private String url;

    @Column(name = "registry_url", length = 500)
    private String registryUrl;

    @Column(name = "repository", length = 200)
    private String repository;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "md5_hash", length = 32)
    private String md5Hash;

    @Column(name = "sha256_hash", length = 64)
    private String sha256Hash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pipeline_execution_id")
    private PipelineExecution pipelineExecution;

    @Column(name = "build_number")
    private Integer buildNumber;

    @Column(name = "git_commit_sha", length = 64)
    private String gitCommitSha;

    @Column(name = "git_branch", length = 200)
    private String gitBranch;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "is_pinned", nullable = false)
    private Boolean isPinned = false;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @Column(name = "cleanup_status", length = 20, nullable = false)
    private String cleanupStatus = "NONE";
}
