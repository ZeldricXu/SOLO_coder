package com.datamigrate.entity;

import com.datamigrate.common.CheckpointType;
import com.datamigrate.common.ResumeStrategy;
import com.datamigrate.common.TaskStatus;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "migrate_tasks")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MigrateTask {

    @Id
    @Column(name = "task_id", length = 64, unique = true, nullable = false)
    private String taskId;

    @Column(name = "task_name", nullable = false)
    private String taskName;

    @Column(name = "source_type", length = 32)
    private String sourceType;

    @Column(name = "source_host")
    private String sourceHost;

    @Column(name = "source_port")
    private Integer sourcePort;

    @Column(name = "source_database")
    private String sourceDatabase;

    @Column(name = "source_username")
    private String sourceUsername;

    @Column(name = "source_password")
    private String sourcePassword;

    @Column(name = "source_table")
    private String sourceTable;

    @Column(name = "source_query", columnDefinition = "TEXT")
    private String sourceQuery;

    @Column(name = "target_type", length = 32)
    private String targetType;

    @Column(name = "target_host")
    private String targetHost;

    @Column(name = "target_port")
    private Integer targetPort;

    @Column(name = "target_database")
    private String targetDatabase;

    @Column(name = "target_username")
    private String targetUsername;

    @Column(name = "target_password")
    private String targetPassword;

    @Column(name = "target_table")
    private String targetTable;

    @Column(name = "primary_key_field")
    private String primaryKeyField;

    @Column(name = "batch_size")
    private Integer batchSize = 100;

    @Column(name = "max_retry_times")
    private Integer maxRetryTimes = 3;

    @Column(name = "auto_verify")
    private Boolean autoVerify = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "resume_strategy", length = 32)
    private ResumeStrategy resumeStrategy = ResumeStrategy.FROM_BREAKPOINT;

    @Enumerated(EnumType.STRING)
    @Column(name = "checkpoint_type", length = 32)
    private CheckpointType checkpointType = CheckpointType.BY_BATCH;

    @Column(name = "checkpoint_interval")
    private Long checkpointInterval = 1000L;

    @Column(name = "enable_resume")
    private Boolean enableResume = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32)
    private TaskStatus status = TaskStatus.PENDING;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @OneToMany(mappedBy = "task", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JsonManagedReference
    private List<MappingRule> mappingRules = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
