package com.cicd.server.entity;

import com.cicd.common.enums.PipelineStatus;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "job_executions")
public class JobExecution extends BaseEntity {

    @Column(name = "job_name", nullable = false, length = 100)
    private String jobName;

    @Column(name = "job_order", nullable = false)
    private Integer jobOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stage_execution_id", nullable = false)
    private StageExecution stageExecution;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PipelineStatus status;

    @Column(name = "runner_id")
    private Long runnerId;

    @Column(name = "runner_tags", length = 500)
    private String runnerTags;

    @Column(name = "workspace_path", length = 500)
    private String workspacePath;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "duration_seconds")
    private Long durationSeconds;

    @Column(name = "log_url", length = 500)
    private String logUrl;

    @OneToMany(mappedBy = "jobExecution", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<StepExecution> steps;
}
