package com.cicd.server.entity;

import com.cicd.common.enums.PipelineStatus;
import com.cicd.common.enums.TriggerType;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "pipeline_executions")
public class PipelineExecution extends BaseEntity {

    @Column(name = "execution_number", nullable = false)
    private Integer executionNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pipeline_id", nullable = false)
    private Pipeline pipeline;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PipelineStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 30)
    private TriggerType triggerType;

    @Column(name = "triggered_by", length = 100)
    private String triggeredBy;

    @Column(name = "branch_name", length = 200)
    private String branchName;

    @Column(name = "commit_sha", length = 64)
    private String commitSha;

    @Column(name = "commit_message", length = 1000)
    private String commitMessage;

    @Column(name = "commit_author", length = 200)
    private String commitAuthor;

    @Column(name = "params_json", columnDefinition = "TEXT")
    private String paramsJson;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "duration_seconds")
    private Long durationSeconds;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @OneToMany(mappedBy = "execution", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<StageExecution> stages;

    @OneToMany(mappedBy = "pipelineExecution", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Artifact> artifacts;

    @OneToMany(mappedBy = "pipelineExecution", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Deployment> deployments;

    @OneToOne(mappedBy = "pipelineExecution", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private Approval approval;
}
