package com.cicd.server.entity;

import com.cicd.common.enums.DeploymentStrategy;
import com.cicd.common.enums.PipelineStatus;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "deployments")
public class Deployment extends BaseEntity {

    @Column(name = "deployment_number", nullable = false)
    private Integer deploymentNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "environment_id", nullable = false)
    private Environment environment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pipeline_execution_id")
    private PipelineExecution pipelineExecution;

    @Column(nullable = false, length = 200)
    private String appName;

    @Column(nullable = false, length = 200)
    private String version;

    @Column(name = "image", length = 500)
    private String image;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DeploymentStrategy strategy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PipelineStatus status;

    @Column(name = "deployed_by", length = 100)
    private String deployedBy;

    @Column(name = "target_replicas")
    private Integer targetReplicas;

    @Column(name = "current_replicas")
    private Integer currentReplicas;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "duration_seconds")
    private Long durationSeconds;

    @Column(name = "smoke_test_passed")
    private Boolean smokeTestPassed;

    @Column(name = "is_rollback", nullable = false)
    private Boolean isRollback = false;

    @Column(name = "previous_deployment_id")
    private Long previousDeploymentId;

    @Column(name = "rollback_reason", length = 1000)
    private String rollbackReason;

    @Column(name = "canary_traffic_percent")
    private Integer canaryTrafficPercent;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;
}
