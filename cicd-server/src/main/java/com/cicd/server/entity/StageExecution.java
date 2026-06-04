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
@Table(name = "stage_executions")
public class StageExecution extends BaseEntity {

    @Column(name = "stage_name", nullable = false, length = 100)
    private String stageName;

    @Column(name = "stage_order", nullable = false)
    private Integer stageOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_id", nullable = false)
    private PipelineExecution execution;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PipelineStatus status;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "duration_seconds")
    private Long durationSeconds;

    @OneToMany(mappedBy = "stageExecution", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<JobExecution> jobs;
}
