package com.cicd.server.entity;

import com.cicd.common.enums.PipelineStatus;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "pipelines")
public class Pipeline extends BaseEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "yaml_definition", columnDefinition = "TEXT", nullable = false)
    private String yamlDefinition;

    @Column(name = "template_id")
    private Long templateId;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "latest_status", length = 30)
    private PipelineStatus latestStatus;

    @Column(name = "latest_execution_id")
    private Long latestExecutionId;

    @Column(name = "concurrent_builds", nullable = false)
    private Integer concurrentBuilds = 1;

    @OneToMany(mappedBy = "pipeline", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<PipelineExecution> executions;
}
