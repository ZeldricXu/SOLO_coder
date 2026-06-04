package com.cicd.server.entity;

import com.cicd.common.enums.PipelineStatus;
import com.cicd.common.enums.StepType;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "step_executions")
public class StepExecution extends BaseEntity {

    @Column(name = "step_name", nullable = false, length = 100)
    private String stepName;

    @Column(name = "step_order", nullable = false)
    private Integer stepOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_execution_id", nullable = false)
    private JobExecution jobExecution;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private StepType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PipelineStatus status;

    @Column(name = "command", columnDefinition = "TEXT")
    private String command;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "duration_seconds")
    private Long durationSeconds;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "output", columnDefinition = "TEXT")
    private String output;
}
